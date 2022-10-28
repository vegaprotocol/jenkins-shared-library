void call() {
    // NOTE: environment variables PSSH_USER and PSSH_KEYFILE are used by veganet.sh script
    /* groovylint-disable-next-line NoDef, VariableTypeRequired */
    def sshCredentials = sshUserPrivateKey(
        credentialsId: 'ssh-vega-network',
        keyFileVariable: 'PSSH_KEYFILE',
        usernameVariable: 'PSSH_USER'
    )

    NODE_NAME = ''
    SHORT_NODE = ''
    ETH_ADDRESS = ''

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
                                params.RANDOM_NODE || params.JOIN_AS_VALIDATOR
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
            stage('Prepare node') {
                when {
                    expression {
                        params.JOIN_AS_VALIDATOR
                    }
                }
                steps {
                    script {
                        switch(env.NET_NAME) {
                            case 'devnet1':
                                NODE_NAME = 'n05.devnet1.vega.xyz'
                                SHORT_NODE = 'n05'
                                break
                            default:
                                error("You can't run 'JOIN_AS_VALIDATOR' for ${env.NET_NAME}")
                        }
                        if (!params.VEGA_VERSION  && !params.RELEASE_VERSION) {
                            error('VEGA_VERSION or RELEASE_VERSION must be set when recreating a node')
                        }
                        if (!params.USE_REMOTE_SNAPSHOT) {
                            error("If joining as validator you need to set USE_REMOTE_SNAPSHOT or implemenet a sleep procedure in this pipeline.")
                        }
                        if (!params.UNSAFE_RESET_ALL) {
                            error('You need to set UNSAFE_RESET_ALL when JOIN_AS_VALIDATOR to wipe out old data from the machine.')
                        }
                    }
                    print("""Run command that:
                    - Generates New Secrets for ${NODE_NAME} on ${env.NET_NAME} - all of them: vega, eth, tendermint,
                    - Unstake Vega Tokens on ERC20 Bridge for Old VegaPubKey - this will cause the old validator to be removed at the end of epoch
                    - Stake Vega Tokens on ERC20 Bridge to Newly generated VegaPubKey
                    """)
                    withDevopstools(
                        command: "validator join --node ${SHORT_NODE} --generate-new-secrets --unstake-from-old-secrets --stake"
                    )
                    ETH_ADDRESS = withDevopstools(
                        command: "validator join --node ${SHOT_NODE} --get-eth-to-submit-bundle",
                        returnStdout: true,
                    )
                }
            }
            stage('Build vega, data-node, vegawallet and visor') {
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
            stage('Run ansible playbook') {
                environment {
                    ANSIBLE_VAULT_PASSWORD_FILE = credentials('ansible-vault-password')
                    HASHICORP_VAULT_ADDR = 'https://vault.ops.vega.xyz'
                }
                steps {
                    withCredentials([usernamePassword(credentialsId: 'hashi-corp-vault-jenkins-approle', passwordVariable: 'HASHICORP_VAULT_SECRET_ID', usernameVariable:'HASHICORP_VAULT_ROLE_ID')]) {
                        withCredentials([sshCredentials]) {
                            script {
                                if (params.RANDOM_NODE) {
                                    if (params.JOIN_AS_VALIDATOR) {
                                        echo "!!!!! you can't assign random node for 'JOIN_AS_VALIDATOR' !!!!!!"
                                        echo "!!!! ${NODE_NAME} is used instead"
                                    }
                                    else {
                                        dir('devopstools') {
                                            NODE_NAME = sh (
                                                script: "go run main.go live nodename --network ${env.NET_NAME} --random",
                                                returnStdout: true,
                                            ).trim()
                                        }
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
                                // Note: environment variables PSSH_KEYFILE and PSSH_USER are set by withCredentials wrapper
                                sh label: 'ansible playbooks/playbook-barenode.yaml', script: """#!/bin/bash -e
                                    ansible-playbook \
                                        --diff \
                                        -u "\${PSSH_USER}" \
                                        --private-key "\${PSSH_KEYFILE}" \
                                        --inventory inventories \
                                        --limit "${NODE_NAME ?: params.NODE}" \
                                        --tag "${params.ACTION}" \
                                        --extra-vars '{"release_version": "${params.RELEASE_VERSION}", "unsafe_reset_all": ${params.UNSAFE_RESET_ALL}, "use_remote_snapshot": ${params.USE_REMOTE_SNAPSHOT}}, "eth_address_to_submit_multisig_changes": "${ETH_ADDRESS}"' \
                                        playbooks/playbook-barenode.yaml
                                """
                            }
                        }
                    }
                }
            }
            stage('Post configuration') {
                when {
                    expression {
                        params.JOIN_AS_VALIDATOR
                    }
                }
                steps {
                    withDevopstools(
                        command: "--node ${SHORT_NODE} --self-delegate"
                    )
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
                                name: "Restart node (`${NODE_NAME}`) from local snapshot has failed.",
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
                                name: "Restart node (`${NODE_NAME}`) from local snapshot has succeeded.",
                            )
                        )
                    }
                }
            }
        }
    }
}
