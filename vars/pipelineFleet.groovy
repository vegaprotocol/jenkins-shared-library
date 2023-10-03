void call() {
    // NOTE: environment variables PSSH_USER and PSSH_KEYFILE are used by veganet.sh script
    /* groovylint-disable-next-line NoDef, VariableTypeRequired */
    def sshCredentials = sshUserPrivateKey(
        credentialsId: 'ssh-vega-network',
        keyFileVariable: 'PSSH_KEYFILE',
        usernameVariable: 'PSSH_USER'
    )

    MACHINE_NAME = null
    ALERT_SILENCE_ID = ''

    pipeline {
        agent {
            label params.NODE_LABEL
        }
        options {
            skipDefaultCheckout()
            timeout(time: params.TIMEOUT, unit: 'MINUTES')
            timestamps()
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
                        MACHINE_NAME = params.MACHINE_NAME.trim()
                        currentBuild.description = "${MACHINE_NAME}"
                        if (params.DRY_RUN) {
                            currentBuild.description += " [DRY RUN]"
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
                                node: MACHINE_NAME,
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
                    withCredentials([usernamePassword(credentialsId: 'hashi-corp-vault-jenkins-approle', passwordVariable: 'HASHICORP_VAULT_SECRET_ID', usernameVariable:'HASHICORP_VAULT_ROLE_ID')]) {
                        withCredentials([sshCredentials]) {
                            dir('ansible') {
                                sh label: "ansible playbooks/playbook-barenode-common.yaml", script: """#!/bin/bash -e
                                    ansible-playbook \
                                        ${params.DRY_RUN ? '--check' : ''} \
                                        --diff \
                                        -u "\${PSSH_USER}" \
                                        --private-key "\${PSSH_KEYFILE}" \
                                        --inventory inventories \
                                        --limit "${MACHINE_NAME}" \
                                        --extra-vars '{"update_accounts": ${params.UPDATE_ACCOUNTS}}' \
                                        playbooks/${env.ANSIBLE_PLAYBOOK}
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
