void call() {
    // NOTE: environment variables PSSH_USER and PSSH_KEYFILE are used by veganet.sh script
    /* groovylint-disable-next-line NoDef, VariableTypeRequired */
    def sshCredentials = sshUserPrivateKey(
        credentialsId: 'ssh-vega-network',
        keyFileVariable: 'PSSH_KEYFILE',
        usernameVariable: 'PSSH_USER'
    )

    ANSIBLE_LIMIT = 'non-existing'

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
        }
        stages {
            stage('CI Config') {
                steps {
                    sh "printenv"
                    echo "params=${params.inspect()}"
                    script {
                        currentBuild.description = "action: ${params.ACTION}, node: ${params.NODE}"
                        if (params.DRY_RUN) {
                            currentBuild.description += " [DRY RUN]"
                        }
                        if (params.NODE?.toLowerCase() == 'all') {
                            ANSIBLE_LIMIT = env.NET_NAME
                        } else if (params.NODE?.trim()) {
                            ANSIBLE_LIMIT = params.NODE.trim()
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
                steps {
                    catchError {
                        script {
                            ALERT_SILENCE_ID = alert.disableAlerts(
                                node: params.NODE,
                                duration: 5, // minutes
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
                                        --limit "${ANSIBLE_LIMIT}" \
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
                catchError {
                    script {
                        alert.enableAlerts(silenceID: ALERT_SILENCE_ID, delay: 2)
                    }
                }
            }
            unsuccessful {
                catchError {
                    script {
                        alert.enableAlerts(silenceID: ALERT_SILENCE_ID, delay: 1)
                    }
                }
            }
        }
    }
}
