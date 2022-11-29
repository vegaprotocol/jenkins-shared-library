boolean isValidatorJoiningAndLeaving(Map<?, ?> params) {
    return params != null && params.ACTION == "create-node" && params.JOIN_AS_VALIDATOR
}

void updateBuildName(Map<?, ?> params) {
    if (currentBuild == null || currentBuild.number == null) {
        return
    }
    buildNumber = currentBuild.number

    if (isValidatorJoiningAndLeaving(params)) {
        currentBuild.displayName = sprintf("#%d Validator joining & leaving", buildNumber)
        return
    }
    if (params.RANDOM_NODE) {
        currentBuild.displayName = sprintf("#%d Random node restart", buildNumber)
        return
    }
}

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
    ANSIBLE_VARS = ''
    ANSIBLE_VARS_DICT = [:]
    String VEGA_VERSION_FROM_STATISTICS = ''

    RELEASE_VERSION = null
    DOCKER_VERSION = null

    pipeline {
        agent any
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
                    updateBuildName(params)
                    sh "printenv"
                    echo "params=${params.inspect()}"
                    script {
                        currentBuild.description = "action: ${params.ACTION}"
                        (RELEASE_VERSION, DOCKER_VERSION) = vegavisorConfigureReleaseVersion(params.RELEASE_VERSION, params.DOCKER_VERSION)
                    }
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
                        if (!params.VEGA_VERSION  && !RELEASE_VERSION) {
                            statisticsEndpointOut = vegautils.networkStatistics(env.NET_NAME)
                            if (statisticsEndpointOut['statistics'] == null || statisticsEndpointOut['statistics']['appVersion'] == null) {
                                println('Failed to get vega network statistics to find the network version')
                                error('VEGA_VERSION or RELEASE_VERSION must be set when recreating a node')
                            }

                            VEGA_VERSION_FROM_STATISTICS = statisticsEndpointOut['statistics']['appVersion']
                            println('RELEASE_VERSION and VEGA_VERASION NOT SPECIFIED BUT VERSION HAS BEEN COLLECTED FROM THE NETWORK STATISTICS. THE VERSION IS: ' + VEGA_VERSION_FROM_STATISTICS)
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
                    script {
                        ETH_ADDRESS = withDevopstools(
                            command: "validator join --node ${SHORT_NODE} --get-eth-to-submit-bundle",
                            returnStdout: true,
                        ).trim()
                        ANSIBLE_VARS_DICT = [
                            'healthcheck_type': 'time_check',
                        ]
                    }
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

                                currentBuild.description += ", node: ${NODE_NAME}"

                                if (params.VEGA_VERSION) {
                                    sh label: 'copy binaries to ansible', script: """#!/bin/bash -e
                                        cp ./bin/vega ./ansible/roles/barenode/files/bin/
                                        cp ./bin/data-node ./ansible/roles/barenode/files/bin/
                                        cp ./bin/visor ./ansible/roles/barenode/files/bin/
                                    """
                                }

                                // create json with function instead of manual
                                ANSIBLE_VARS = writeJSON(
                                    returnText: true,
                                    json: ANSIBLE_VARS_DICT + [
                                        release_version: (RELEASE_VERSION ?: VEGA_VERSION_FROM_STATISTICS),
                                        unsafe_reset_all: params.UNSAFE_RESET_ALL,
                                        use_remote_snapshot: params.USE_REMOTE_SNAPSHOT,
                                        eth_address_to_submit_multisig_changes: ETH_ADDRESS,
                                    ].findAll{ key, value -> value != null }
                                )
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
                                        --extra-vars '${ANSIBLE_VARS}' \
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
                        command: "validator join --node ${SHORT_NODE} --self-delegate --send-ethereum-events"
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
                        slackSend(channel: "#snapshot-notify",
                            color: 'danger',
                            message: slack.composeMessage(
                                branch: '',
                                name: "Restart node (`${NODE_NAME}`) from local snapshot has failed.",
                            )
                        )
                    }
                    if (isValidatorJoiningAndLeaving(params)) {
                        slackSend(channel: "#validator-joining-and-leaving-notify",
                            color: 'danger',
                            message: slack.composeMessage(
                                branch: '',
                                name: "Validator joining & leaving failed for the `${NODE_NAME}` node.",
                            )
                        )
                    }
                }
            }
            success {
                script {
                    if (params.RANDOM_NODE) {
                        slackSend(channel: "#snapshot-notify",
                            color: 'good',
                            message: slack.composeMessage(
                                branch: '',
                                name: "Restart node (`${NODE_NAME}`) from local snapshot has succeeded.",
                            )
                        )
                    }
                    if (isValidatorJoiningAndLeaving(params)) {
                        slackSend(channel: "#validator-joining-and-leaving-notify",
                            color: 'good',
                            message: slack.composeMessage(
                                branch: '',
                                name: "Validator joining & leaving succeed for the `${NODE_NAME}` node.",
                            )
                        )
                    }
                }
            }
        }
    }
}
