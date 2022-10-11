void call() {
    // NOTE: environment variables PSSH_USER and PSSH_KEYFILE are used by veganet.sh script
    /* groovylint-disable-next-line NoDef, VariableTypeRequired */
    def sshCredentials = sshUserPrivateKey(
        credentialsId: 'ssh-vega-network',
        keyFileVariable: 'PSSH_KEYFILE',
        usernameVariable: 'PSSH_USER'
    )

    pipeline {
        agent any
        options {
            skipDefaultCheckout()
            timeout(time: 40, unit: 'MINUTES')
            timestamps()
            lock(resource: env.NET_NAME)
            ansiColor('x-term')
        }
        environment {
            PATH = "${env.WORKSPACE}/bin:${env.PATH}"
        }
        stages {
            stage('CI Config') {
                steps {
                    sh "printenv"
                    echo "params=${params.inspect()}"
                }
            }
            stage('Checkout') {
                parallel {
                    stage('vega'){
                        when {
                            expression { params.VEGA_VERSION }
                        }
                        steps {
                            script {
                                gitClone(
                                    directory: 'vega',
                                    branch: params.VEGA_VERSION,
                                    vegaUrl: 'vega',
                                )
                            }
                        }
                    }
                    stage('ansible'){
                        steps {
                            gitClone(
                                vegaUrl: 'ansible',
                                directory: 'ansible',
                                branch: params.ANSIBLE_BRANCH,
                            )
                        }
                    }
                    stage('devopstools') {
                        when {
                            expression {
                                params.RANDOM_NODE
                            }
                        }
                        steps {
                            gitClone(
                                vegaUrl: 'devopstools',
                                directory: 'devopstools',
                                branch: params.DEVOPSTOOLS_BRANCH,
                            )
                            dir ('devopstools') {
                                sh 'go mod download'
                            }
                        }
                    }
                }
            }
            stage('Build vaga, data-node, vegawallet and visor') {
                when {
                    expression { params.VEGA_VERSION }
                }
                steps {
                    dir('vega') {
                        sh label: 'Compile', script: """#!/bin/bash -e
                            go build -v \
                                -o ../bin/ \
                                ./cmd/vega \
                                ./cmd/data-node \
                                ./cmd/vegawallet \
                                ./cmd/visor
                        """
                    }
                    dir('bin') {
                        sh label: 'Sanity check: vega', script: '''#!/bin/bash -e
                            file ./vega
                            ./vega version
                        '''
                        sh label: 'Sanity check: data-node', script: '''#!/bin/bash -e
                            file ./data-node
                            ./data-node version
                        '''
                        sh label: 'Sanity check: vegawallet', script: '''#!/bin/bash -e
                            file ./vegawallet
                            ./vegawallet version
                        '''
                        sh label: 'Sanity check: visor', script: '''#!/bin/bash -e
                            file ./visor
                            ./visor --help
                        '''
                    }
                }
            }
            stage('Restart Node') {
                environment {
                    ANSIBLE_VAULT_PASSWORD_FILE = credentials('ansible-vault-password')
                    HASHICORP_VAULT_ADDR = 'https://vault.ops.vega.xyz'
                }
                steps {
                    withCredentials([usernamePassword(credentialsId: 'hashi-corp-vault-jenkins-approle', passwordVariable: 'HASHICORP_VAULT_SECRET_ID', usernameVariable:'HASHICORP_VAULT_ROLE_ID')]) {
                        withCredentials([sshCredentials]) {
                            script {
                                if (params.RANDOM_NODE) {
                                    dir('devopstools') {
                                        nodeName = sh (
                                            script: "go run main.go live nodename --network ${env.NET_NAME} --random",
                                            returnStdout: true,
                                        ).trim()
                                    }
                                }
                                if (params.VEGA_VERSION) {
                                    sh label: 'copy binaries to ansible', script: """#!/bin/bash -e
                                        cp ./bin/vega ./ansible/roles/barenode/files/bin/
                                        cp ./bin/data-node ./ansible/roles/barenode/files/bin/
                                        cp ./bin/visor ./ansible/roles/barenode/files/bin/
                                    """
                                }
                            }
                            dir('ansible') {
                                // Note: environment variables PSSH_KEYFILE and PSSH_USER
                                //        are set by withCredentials wrapper
                                sh label: 'ansible playbook run', script: """#!/bin/bash -e
                                    ansible-playbook \
                                        --diff \
                                        -u "\${PSSH_USER}" \
                                        --private-key "\${PSSH_KEYFILE}" \
                                        --inventory inventories \
                                        --limit "${nodeName ?: params.NODE}" \
                                        --tag "${action ?: params.ACTION}" \
                                        --extra-vars '{"release_version": "${params.RELEASE_VERSION}", "unsafe_reset_all": ${params.UNSAFE_RESET_ALL}}' \
                                        playbooks/playbook-barenode.yaml
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
            unsuccessful {
                script {
                    if (params.RANDOM_NODE) {
                        slackSend(
                            channel: "#snapshot-notify",
                            color: 'danger',
                            message: slack.composeMessage(
                                branch: '',
                                name: "Restart node (`${nodeName}`) from local snapshot has failed.",
                            )
                        )
                    }
                }
            }
            success {
                script {
                    if (params.RANDOM_NODE) {
                        slackSend(
                            channel: "#snapshot-notify",
                            color: 'good',
                            message: slack.composeMessage(
                                branch: '',
                                name: "Restart node (`${nodeName}`) from local snapshot has succeeded.",
                            )
                        )
                    }
                }
            }
        }
    }
}
