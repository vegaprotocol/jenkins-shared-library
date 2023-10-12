void call() {
    // NOTE: environment variables PSSH_USER and PSSH_KEYFILE are used by veganet.sh script
    /* groovylint-disable-next-line NoDef, VariableTypeRequired */
    def sshCredentials = sshUserPrivateKey(
        credentialsId: 'ssh-vega-network',
        keyFileVariable: 'PSSH_KEYFILE',
        usernameVariable: 'PSSH_USER'
    )

    def duration = {
        return currentBuild.durationString - ' and counting'
    }

    def versionTag = 'UNKNOWN'
    def protocolUpgradeBlock = -1
    RELEASE_VERSION = null

    RELEASE_VERSION = null
    DOCKER_VERSION = null
    ANSIBLE_VARS = ''

    pipeline {
        agent {
            label params.NODE_LABEL
        }
        options {
            skipDefaultCheckout()
            timeout(time: params.TIMEOUT, unit: 'MINUTES')
            timestamps()
            ansiColor('xterm')
            lock(resource: env.NET_NAME)
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
                        (RELEASE_VERSION, DOCKER_VERSION) = vegavisorConfigureReleaseVersion(params.RELEASE_VERSION, params.DOCKER_VERSION)
                    }
                    echo "Release version: ${RELEASE_VERSION}"
                }
            }
            stage('Checkout') {
                parallel {
                    stage('devopstools'){
                        steps {
                            gitClone(
                                directory: 'devopstools',
                                branch: params.DEVOPSTOOLS_BRANCH,
                                vegaUrl: 'devopstools',
                            )
                        }
                    }
                    stage('ansible'){
                        steps {
                            gitClone(
                                directory: 'ansible',
                                branch: params.ANSIBLE_BRANCH,
                                vegaUrl: 'ansible',
                            )
                        }
                    }
                    stage('k8s'){
                        when {
                            expression { DOCKER_VERSION }
                        }
                        steps {
                            gitClone(
                                directory: 'k8s',
                                branch: 'main',
                                vegaUrl: 'k8s',
                            )
                        }
                    }
                }
            }
            stage('Prepare'){
                parallel {
                    stage('Setup devopstools') {
                        steps {
                            dir('devopstools') {
                                sh label: 'Download dependencies', script: '''#!/bin/bash -e
                                    go mod download
                                '''
                            }
                        }
                    }
                }
            }  // End: Prepare
            stage('Protocol Upgrade Network') {
                when {
                    expression { env.ANSIBLE_LIMIT }
                }
                environment {
                    ANSIBLE_VAULT_PASSWORD_FILE = credentials('ansible-vault-password')
                    HASHICORP_VAULT_ADDR = 'https://vault.ops.vega.xyz'
                }
                steps {
                    script {
                        if (params.UPGRADE_BLOCK) {
                            protocolUpgradeBlock = params.UPGRADE_BLOCK as int
                        } else {
                            dir('devopstools') {
                                protocolUpgradeBlock = sh(
                                    script: "go run main.go network stats --block --network ${env.NET_NAME}",
                                    returnStdout: true,
                                ).trim() as int
                                protocolUpgradeBlock += 400
                            }
                        }
                        // create json with function instead of manual
                        ANSIBLE_VARS = writeJSON(
                            returnText: true,
                            json: [
                                protocol_upgrade_version: RELEASE_VERSION,
                                protocol_upgrade_block: protocolUpgradeBlock,
                                protocol_upgrade_manual_install: params.MANUAL_INSTALL,
                                protocol_upgrade_render_configs: params.RENDER_CONFIGS,
                                perform_network_operations: params.PERFORM_NETWORK_OPERATIONS,
                                update_system_configuration: params.UPDATE_SYSTEM_CONFIGURATION,
                            ].findAll{ key, value -> value != null }
                        )
                    }
                    dir('ansible') {
                        withCredentials([usernamePassword(credentialsId: 'hashi-corp-vault-jenkins-approle', passwordVariable: 'HASHICORP_VAULT_SECRET_ID', usernameVariable:'HASHICORP_VAULT_ROLE_ID')]) {
                            withCredentials([sshCredentials]) {
                                // Note: environment variables PSSH_KEYFILE and PSSH_USER
                                //        are set by withCredentials wrapper
                                sh label: 'ansible playbook run', script: """#!/bin/bash -e
                                    ansible-playbook ${params.DRY_RUN ? '--check' : ''} \
                                        --diff \
                                        -u "\${PSSH_USER}" \
                                        --private-key "\${PSSH_KEYFILE}" \
                                        --inventory inventories \
                                        --limit "${env.ANSIBLE_LIMIT}" \
                                        --tag protocol-upgrade \
                                        --extra-vars '${ANSIBLE_VARS}' \
                                        playbooks/playbook-barenode-protocol-upgrade.yaml
                                """
                            }
                        }
                    }
                }
            }
            stage('Market actions') {
                stages {
                    stage('Create markets & provide lp'){
                        when {
                            expression {
                                params.CREATE_MARKETS && params.PERFORM_NETWORK_OPERATIONS
                            }
                        }
                        steps {
                            sleep 60 // TODO: Add wait for network to replay all of the ethereum events...
                            retry(3) {
                                lock(resource: "ethereum-minter-${env.NET_NAME}") {
                                    withDevopstools(
                                        command: 'market propose --all'
                                    )
                                }
                            }
                            sleep 30 * 7
                            retry(3) {
                                lock(resource: "ethereum-minter-${env.NET_NAME}") {
                                    withDevopstools(
                                        command: 'market provide-lp'
                                    )
                                }
                            }
                        }
                    }
                    stage('Set up referral program') {
                        when {
                            expression {
                                params.SETUP_REFERRAL_PROGRAM && params.PERFORM_NETWORK_OPERATIONS
                            }
                        }
                        steps {
                            withDevopstools(
                                command: 'propose referral --setup-referral-program'
                            )
                        }
                    }
                    stage('Set up volume discount program') {
                        when {
                            expression {
                                params.SETUP_VOLUME_DISCOUNT_PROGRAM && params.PERFORM_NETWORK_OPERATIONS
                            }
                        }
                        steps {
                            withDevopstools(
                                command: 'propose volume-discount --setup-volume-discount-program'
                            )
                        }
                    }
                    stage('Update network params') {
                        when {
                            epxression {
                                params.UPDATE_NETWORK_PARAMS && params.PERFORM_NETWORK_OPERATIONS
                            }
                        }
                        steps {
                            withDevopsTools(
                                command: 'incentive network-params'
                            )
                        }
                    }
                    stage('Top up bots') {
                        when {
                            allOf {
                                expression {
                                    params.TOP_UP_BOTS && params.PERFORM_NETWORK_OPERATIONS
                                }
                            }
                        }
                        steps {
                            // propagate result only when bots need to join referral program
                            build(
                                job: "private/Deployments/${env.NET_NAME}/Topup-Bots",
                                propagate: params.JOIN_BOTS_TO_REFERRAL_PROGRAM,
                                wait: params.JOIN_BOTS_TO_REFERRAL_PROGRAM,
                            )
                        }
                    }
                    stage('Join bots to referral program') {
                        when {
                            expression {
                                params.JOIN_BOTS_TO_REFERRAL_PROGRAM && params.PERFORM_NETWORK_OPERATIONS
                            }
                        }
                        options {
                            lock(resource: "ethereum-minter-${env.NET_NAME}")
                            retry(3)
                        }
                        steps {
                            withDevopsTools(
                                command: 'bot referral --setup'
                            )
                        }
                    }
                }
            }
            stage('Update vegawallet service') {
                when {
                    expression { DOCKER_VERSION }
                    expression {
                        env.NET_NAME == 'fairground'
                    }
                }
                steps {
                    script {
                        // ['vegawallet', 'faucet'].each { app ->
                        ['vegawallet'].each { app ->
                            releaseKubernetesApp(
                                networkName: env.NET_NAME,
                                application: app,
                                directory: 'k8s',
                                makeCheckout: false,
                                version: DOCKER_VERSION,
                                forceRestart: false,
                                timeout: 5,
                                wait: false,
                            )
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
                slackSend(
                    channel: '#env-deploy',
                    color: 'good',
                    message: ":page_with_curl: protocol successfully upgraded to ${RELEASE_VERSION} on `${env.NET_NAME}` <${env.RUN_DISPLAY_URL}|more> (${duration()}) :rocket:",
                )
            }
            unsuccessful {
                slackSend(
                    channel: '#env-deploy',
                    color: 'danger',
                    message: ":rolled_up_newspaper: protocol upgrade failed on `${env.NET_NAME}` <${env.RUN_DISPLAY_URL}|more> (${duration()}) :boom:",
                )
            }
        }
    }
}
