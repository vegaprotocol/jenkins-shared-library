void call() {
    // NOTE: environment variables PSSH_USER and PSSH_KEYFILE are used by veganet.sh script
    /* groovylint-disable-next-line NoDef, VariableTypeRequired */
    def sshCredentials = sshUserPrivateKey(
        credentialsId: 'ssh-vega-network',
        keyFileVariable: 'PSSH_KEYFILE',
        usernameVariable: 'PSSH_USER'
    )

    ANSIBLE_LIMIT = 'non-existing'
    ANSIBLE_VARS = ''

    ALERT_DISABLE_NODE = null
    ALERT_SILENCE_ID = ''

    pipeline {
        agent {
            label params.NODE_LABEL
        }
        options {
            skipDefaultCheckout()
            timeout(time: params.TIMEOUT, unit: 'MINUTES')
            timestamps()
            // allow disabling lock when provisoining new nodes
            lock(resource: params.DISABLE_LOCK ? "${Math.abs(new Random().nextInt(9999))}" : env.NET_NAME)
            ansiColor('xterm')
        }
        environment {
            PATH = "${env.WORKSPACE}/bin:${env.PATH}"
            GOBIN = "${env.WORKSPACE}/gobin"
        }
        stages {
            stage('CI Config') {
                steps {
                    sh "printenv"
                    echo "params=${params.inspect()}"
                    script {
                        vegautils.commonCleanup()
                        currentBuild.description = "node: ${params.NODE} (from ${params.SNAPSHOT_SRC_MACHINE}, ${params.INITIAL_SETUP ? 'with' : 'NO'} initial setup, ${params.START_AT_THE_END ? 'with' : 'NO'} start at the end)"
                        if (params.DRY_RUN) {
                            currentBuild.description += " [DRY RUN]"
                        }
                        if (params.NODE?.trim()) {
                            ANSIBLE_LIMIT = params.NODE.trim()
                            ALERT_DISABLE_NODE = ANSIBLE_LIMIT
                        } else {
                            error "cannot run ansible: NODE parameter is not set"
                        }
                    }
                }
            }
            stage('Checkout') {
                parallel {
                    stage('ansible'){
                        steps {
                            gitClone(
                                vegaUrl: 'ansible',
                                directory: 'ansible',
                                branch: params.ANSIBLE_BRANCH,
                            )
                        }
                    }
                }
            }
            stage('Disable Alerts') {
                when {
                    not { expression { params.DRY_RUN } }
                }
                steps {
                    retry (3) {
                        script {
                            ALERT_SILENCE_ID = alert.disableAlerts(
                                environment: ALERT_DISABLE_ENV,
                                node: ALERT_DISABLE_NODE,
                                duration: params.TIMEOUT, // minutes
                            )
                        }
                    }
                }
            }
            stage('Initial setup') {
                when {
                    expression { params.INITIAL_SETUP }
                }
                environment {
                    ANSIBLE_VAULT_PASSWORD_FILE = credentials('ansible-vault-password')
                    HASHICORP_VAULT_ADDR = 'https://vault.ops.vega.xyz'
                }
                steps {
                    withCredentials([
                        sshCredentials,
                        usernamePassword(
                            credentialsId: 'hashi-corp-vault-jenkins-approle',
                            passwordVariable: 'HASHICORP_VAULT_SECRET_ID',
                            usernameVariable:'HASHICORP_VAULT_ROLE_ID'
                        ),
                        usernamePassword(
                            credentialsId: 'digitalocean-s3-credentials',
                            passwordVariable: 'AWS_SECRET_ACCESS_KEY',
                            usernameVariable: 'AWS_ACCESS_KEY_ID'
                        ),
                    ]) {
                        dir('ansible') {
                            sh label: "Barenode common", script: """#!/bin/bash -e
                                ansible-playbook \
                                    ${params.DRY_RUN ? '--check' : ''} \
                                    --diff \
                                    -u "\${PSSH_USER}" \
                                    --private-key "\${PSSH_KEYFILE}" \
                                    --inventory inventories \
                                    --limit "${ANSIBLE_LIMIT}" \
                                    playbooks/${env.ANSIBLE_PLAYBOOK_BARENODE_COMMON}
                            """

                            sh label: "Setup(create) and start node", script: """#!/bin/bash -e
                                ansible-playbook \
                                    ${params.DRY_RUN ? '--check' : ''} \
                                    --diff \
                                    -u "\${PSSH_USER}" \
                                    --private-key "\${PSSH_KEYFILE}" \
                                    --inventory inventories \
                                    --limit "${ANSIBLE_LIMIT}" \
                                    --tag "create-node" \
                                    --extra-vars '{"release_version": "v0.71.4"}' \
                                    playbooks/${env.ANSIBLE_PLAYBOOK_BARENODE}
                            """

                            sh label: "Non restart required changes", script: """#!/bin/bash -e
                                ansible-playbook \
                                    ${params.DRY_RUN ? '--check' : ''} \
                                    --diff \
                                    -u "\${PSSH_USER}" \
                                    --private-key "\${PSSH_KEYFILE}" \
                                    --inventory inventories \
                                    --limit "${ANSIBLE_LIMIT}" \
                                    playbooks/${env.ANSIBLE_PLAYBOOK_NON_RESTART_REQUIRED}
                            """
                        }
                    }
                }
            }
            stage('Apply Changes') {
                environment {
                    ANSIBLE_VAULT_PASSWORD_FILE = credentials('ansible-vault-password')
                    HASHICORP_VAULT_ADDR = 'https://vault.ops.vega.xyz'
                }
                steps {
                    script {
                        // create json with function instead of manual
                        ANSIBLE_VARS = writeJSON(
                            returnText: true,
                            json: [
                                rollback_remote_zfs_snapshot: true,
                                rollback_remote_zfs_snapshot_src_machine: params.SNAPSHOT_SRC_MACHINE,
                            ].findAll{ key, value -> value != null && value != '' }
                        )
                    }
                    withCredentials([
                        sshCredentials,
                        usernamePassword(
                            credentialsId: 'hashi-corp-vault-jenkins-approle',
                            passwordVariable: 'HASHICORP_VAULT_SECRET_ID',
                            usernameVariable:'HASHICORP_VAULT_ROLE_ID'
                        ),
                        usernamePassword(
                            credentialsId: 'digitalocean-s3-credentials',
                            passwordVariable: 'AWS_SECRET_ACCESS_KEY',
                            usernameVariable: 'AWS_ACCESS_KEY_ID'
                        ),
                    ]) {
                        dir('ansible') {
                            sh label: "ansible playbooks/playbook-zfs.yaml", script: """#!/bin/bash -e
                                ansible-playbook \
                                    ${params.DRY_RUN ? '--check' : ''} \
                                    --diff \
                                    -u "\${PSSH_USER}" \
                                    --private-key "\${PSSH_KEYFILE}" \
                                    --inventory inventories \
                                    --limit "${ANSIBLE_LIMIT}" \
                                    --extra-vars '${ANSIBLE_VARS}' \
                                    playbooks/playbook-zfs.yaml
                            """
                        }
                    }
                }
            }
            stage('Start at the end') {
                when {
                    expression { params.START_AT_THE_END }
                }
                environment {
                    ANSIBLE_VAULT_PASSWORD_FILE = credentials('ansible-vault-password')
                    HASHICORP_VAULT_ADDR = 'https://vault.ops.vega.xyz'
                }
                steps {
                    withCredentials([
                        sshCredentials,
                        usernamePassword(
                            credentialsId: 'hashi-corp-vault-jenkins-approle',
                            passwordVariable: 'HASHICORP_VAULT_SECRET_ID',
                            usernameVariable:'HASHICORP_VAULT_ROLE_ID'
                        ),
                        usernamePassword(
                            credentialsId: 'digitalocean-s3-credentials',
                            passwordVariable: 'AWS_SECRET_ACCESS_KEY',
                            usernameVariable: 'AWS_ACCESS_KEY_ID'
                        ),
                    ]) {
                        dir('ansible') {
                            sh label: "Setup(create) and start node", script: """#!/bin/bash -e
                                ansible-playbook \
                                    ${params.DRY_RUN ? '--check' : ''} \
                                    --diff \
                                    -u "\${PSSH_USER}" \
                                    --private-key "\${PSSH_KEYFILE}" \
                                    --inventory inventories \
                                    --limit "${ANSIBLE_LIMIT}" \
                                    --tag "restart-node" \
                                    playbooks/${env.ANSIBLE_PLAYBOOK_BARENODE}
                            """

                            sh label: "Non restart required changes", script: """#!/bin/bash -e
                                ansible-playbook \
                                    ${params.DRY_RUN ? '--check' : ''} \
                                    --diff \
                                    -u "\${PSSH_USER}" \
                                    --private-key "\${PSSH_KEYFILE}" \
                                    --inventory inventories \
                                    --limit "${ANSIBLE_LIMIT}" \
                                    playbooks/${env.ANSIBLE_PLAYBOOK_NON_RESTART_REQUIRED}
                            """
                        }
                    }
                }
            }
        }
        post {
            always {
                cleanWs()
            }
            success {
                script {
                    if (ALERT_SILENCE_ID) {
                        alert.enableAlerts(silenceID: ALERT_SILENCE_ID, delay: 2)
                    }
                }
            }
            unsuccessful {
                script {
                    if (ALERT_SILENCE_ID) {
                        alert.enableAlerts(silenceID: ALERT_SILENCE_ID, delay: 1)
                    }
                }
            }
        }
    }
}
