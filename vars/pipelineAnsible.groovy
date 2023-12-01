void call() {
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
        stages {
            stage('Ansible') {
                environment {
                    ANSIBLE_VAULT_PASSWORD_FILE = credentials('ansible-vault-password')
                    HASHICORP_VAULT_ADDR = 'https://vault.ops.vega.xyz'
                }

                steps {
                    script {
                        gitClone(
                            directory: 'ansible',
                            branch: params.ANSIBLE_BRANCH,
                            vegaUrl: 'ansible',
                        )

                        dir('ansible') {
                                withCredentials([
                                    usernamePassword(
                                        credentialsId: 'hashi-corp-vault-jenkins-approle', 
                                        passwordVariable: 'HASHICORP_VAULT_SECRET_ID', 
                                        usernameVariable:'HASHICORP_VAULT_ROLE_ID',
                                    ),
                                    sshUserPrivateKey(
                                        credentialsId: 'ssh-vega-network',
                                        keyFileVariable: 'PSSH_KEYFILE',
                                        usernameVariable: 'PSSH_USER',
                                    ),
                                ]) {
                                    List<String> paramsList = [
                                        params.DRY_RUN ? '--check' : '',
                                        params.HOST_LIMIT && params.HOST_LIMIT.size() > 0 ? '--limit ' + params.HOST_LIMIT : '',
                                        params.EXTRA_PARAMS,
                                    ]
                                    String extraParams = paramsList.join(' ')

                                    sh label: 'ansible playbook run', script: """#!/bin/bash -e
                                        ansible-playbook """ + extraParams + """ \
                                            --diff \
                                            -u "\${PSSH_USER}" \
                                            --private-key "\${PSSH_KEYFILE}" \
                                            --inventory inventories \
                                            playbooks/""" + params.PLAYBOOK_FILE
                                }
                            
                        }
                    }
                }
            }
        }
    }
}
