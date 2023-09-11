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

    ALERT_DISABLE_ENV = null
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
                        currentBuild.description = "node: ${params.NODE}"
                        if (params.CREATE_LOCAL_ZFS_SNAPSHOT) {
                            currentBuild.description += " create_local( ${params.LOCAL_ZFS_SNAPSHOT_NAME.trim()}, ${params.STOP_SERVICES ? 'stop' : "don't stop"} )"
                        }
                        if (params.DESTROY_LOCAL_ZFS_SNAPSHOT_NAMES.trim()) {
                            currentBuild.description += " destroy_local( ${params.DESTROY_LOCAL_ZFS_SNAPSHOT_NAMES.trim()} )"
                        }
                        if (params.DRY_RUN) {
                            currentBuild.description += " [DRY RUN]"
                        }
                        if (params.NODE?.toLowerCase() == 'all') {
                            ANSIBLE_LIMIT = env.NET_NAME
                            ALERT_DISABLE_ENV = ANSIBLE_LIMIT
                        } else if (params.NODE?.trim()) {
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
                                create_local_zfs_snapshot: params.CREATE_LOCAL_ZFS_SNAPSHOT,
                                local_zfs_snapshot_name: params.LOCAL_ZFS_SNAPSHOT_NAME,
                                stop_services: params.STOP_SERVICES,
                                destroy_local_zfs_snapshot_names: params.DESTROY_LOCAL_ZFS_SNAPSHOT_NAMES,
                            ].findAll{ key, value -> value != null && value != '' }
                        )
                    }
                    withCredentials([usernamePassword(credentialsId: 'hashi-corp-vault-jenkins-approle', passwordVariable: 'HASHICORP_VAULT_SECRET_ID', usernameVariable:'HASHICORP_VAULT_ROLE_ID')]) {
                        withCredentials([sshCredentials]) {
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
