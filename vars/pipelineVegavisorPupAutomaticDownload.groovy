/* groovylint-disable DuplicateNumberLiteral, DuplicateStringLiteral, LineLength, NestedBlockDepth */

void call() {
    String versionToUpgradeNetwork
    String versionToStartNetwork
    String networkDataPath
    Map vegacapsuleNodes

    // apply default values for pipeline
    // params = [
    //     RELEASES_REPO: 'vegaprotocol/vega-dev-releases',
    //     SYSTEM_TESTS_BRANCH: 'visor_pup_tests',
    //     VEGACAPSULE_BRANCH: 'main',
    //     VEGATOOLS_BRANCH: 'develop',
    //     DEVOPSSCRIPTS_BRANCH: 'main',
    //     CREATE_RELEASE: true,
    //     VEGA_BRANCH: 'fix-visor-autoinstall',
    // ] + params


    pipeline {
        agent {
        label 'system-tests-capsule'
        }

        options {
            // skipDefaultCheckout true
            timestamps()
            timeout(time: 60, unit: 'MINUTES')
        }

        stages {
            stage('Init') {
                steps {
                    cleanWs()
                    sh 'printenv'
                    echo "params=${params}"
                    echo "isPRBuild=${isPRBuild()}"

                    script {
                        if (params.CREATE_RELEASE && params.VEGA_BRANCH.length() < 1) {
                            error('params.VEGA_BRANCH cannot be empty when params.CREATE_RELEASE is true')
                        }

                        // do not use already created release, build vega
                        if (params.CREATE_RELEASE) {
                            versionToStartNetwork = params.VEGA_BRANCH
                        }

                        publicIP = agent.getPublicIP()
                        print("The box public IP is: " + publicIP)
                        print("You may want to visit the nomad web interface: http://" + publicIP + ":4646")
                        print("The nomad interface is available only when the tests are running")

                        dir(pipelineDefaults.capsuleSystemTests.systemTestsNetworkDir) {
                            networkDataPath = vegautils.escapePath(env.WORKSPACE + '/' + pipelineDefaults.capsuleSystemTests.systemTestsNetworkDir)
                        }
                    }
                }
            }

            stage('Find releases to start and upgrade network') {
                when {
                    not {
                        expression {
                            params.CREATE_RELEASE
                        }
                    }
                }
                steps {
                    script {
                        String releasesListJSON
                        List devRelasesNames
                        withGHCLI('credentialsId': 'github-vega-ci-bot-artifacts') {
                            releasesListJSON = vegautils.shellOutput('''
                            gh api \
                                --jq '[ .[] | .tag_name ]' \
                                --paginate \
                                -H "Accept: application/vnd.github.v3+json" \
                                /repos/''' + params.RELEASES_REPO + '''/releases \
                            | jq -s 'flatten(1) | sort | reverse'
                            ''')
                        }

                        devRelasesNames = readJSON text: releasesListJSON
                        if (devRelasesNames.size() < 2) {
                            error('Not enough releases found in github repo: ' + params.RELEASES_REPO + '. ' +
                                'At least two releases are requied. One for start the network, second one for upgrade')
                        }
                        versionToUpgradeNetwork = devRelasesNames[0]
                        lastAvailableReleaseSemVersion = versionToUpgradeNetwork.tokenize('-')[0]

                        // Find first lower version than `versionToUpgradeNetwork` to avoid the `upgrade version is too old` error
                        for (releaseName in devRelasesNames) {
                            String releaseSemVer = releaseName.tokenize('-')[0]
                            // latest version higher than this version
                            if (vegautils.semVerCompare(lastAvailableReleaseSemVersion, releaseSemVer) > 0) {
                                versionToStartNetwork = releaseName
                                break
                            }
                        }

                        echo 'This pipeline will start the network with version ' + versionToStartNetwork +
                            ', then perform protocol upgrade to version: ' + versionToUpgradeNetwork

                        versionToStartNetwork = versionToStartNetwork.tokenize('-')[0]
                    }
                }
            }

            stage('Pull repositories') {
                steps {
                    script {
                        List repositories = [
                            [ name: 'vegaprotocol/vega', branch: versionToStartNetwork ],
                            [ name: 'vegaprotocol/system-tests', branch: params.SYSTEM_TESTS_BRANCH ],
                            [ name: 'vegaprotocol/vegacapsule', branch: params.VEGACAPSULE_BRANCH ],
                            [ name: 'vegaprotocol/vegatools', branch: params.VEGATOOLS_BRANCH ],
                            [ name: 'vegaprotocol/devopsscripts', branch: params.DEVOPSSCRIPTS_BRANCH ],
                        ]
                        Map reposSteps = repositories.collectEntries{value -> [
                            value.name,
                            {
                            gitClone([
                                url: 'git@github.com:' + value.name + '.git',
                                branch: value.branch,
                                directory: value.name.split('/')[1],
                                credentialsId: 'vega-ci-bot',
                                timeout: 2,
                            ])
                            }
                        ] }
                        parallel reposSteps
                    }
                }
            }

            stage('make binaries') {
                options {
                    timeout(time: 10, unit: 'MINUTES')
                    retry(3)
                }
                environment {
                    TESTS_DIR = "${networkDataPath}"
                }
                steps {
                    dir('system-tests/scripts') {
                        sh 'make build-binaries'
                        sh 'make vegacapsule-cleanup'
                    }
                    script {
                        vegautils.buildGoBinary('devopsscripts',  networkDataPath + '/devopsscripts', './')
                    }
                }
            }

            stage('create further release and upload binaries') {
                when {
                    expression {
                        params.CREATE_RELEASE
                    }
                }

                steps {
                    script {
                        String newVegaVersion = 'v77.7.7-jenkins-visor-pup-' + currentBuild.number
                        sh 'mkdir -p vega/dist'
                        sh '''sed -i 's/^\\s*cliVersion\\s*=\\s*".*"$/cliVersion="''' + newVegaVersion + '''"/' vega/version/version.go'''
                        vegautils.buildGoBinary('vega', 'dist', './...')

                        dir('vega/dist') {
                            sh './vega version'
                            sh './data-node version'
                            sh 'zip data-node-linux-amd64.zip data-node'
                            sh 'zip vega-linux-amd64.zip vega'

                            withGHCLI('credentialsId': 'github-vega-ci-bot-artifacts') {
                                sh '''gh release create \
                                    --repo ''' + params.RELEASES_REPO + ''' \
                                    ''' + newVegaVersion + ''' \
                                    *.zip'''
                            }
                        }

                        versionToUpgradeNetwork = 'v77.7.7-jenkins-visor-pup-' + currentBuild.number
                    }
                }
            }

            stage('start nomad') {
                steps {
                    script {
                        dir ('system-tests/scripts') {
                            String makeAbsBinaryPath = vegautils.shellOutput('which make')
                            String cwd = vegautils.shellOutput('pwd')

                            sh '''daemonize \
                                -o ''' + networkDataPath + '''/nomad.log \
                                -e ''' + networkDataPath + '''/nomad.log \
                                -c ''' + cwd + ''' \
                                -p ''' + networkDataPath + '''/vegacapsule_nomad.pid \
                                ''' + makeAbsBinaryPath + ''' vegacapsule-start-nomad-only'''
                        }
                    }
                }
            }

            stage('generate network config') {
                environment {
                    PATH = "${networkDataPath}:${env.PATH}"
                    TESTS_DIR = "${networkDataPath}"
                    VEGACAPSULE_CONFIG_FILENAME = "${env.WORKSPACE}/system-tests/vegacapsule/capsule_visor_pup.hcl"
                }

                options {
                    timeout(time: 3, unit: 'MINUTES')
                }

                steps {
                    script {
                        dir('system-tests/scripts') {
                            sh 'make vegacapsule-generate-network'
                            sh 'make vegacapsule-start-network-only'
                            sh 'sleep 60' // wait for some blocks to be produced
                        }
                    }
                }
            }

            stage('Protocol upgrade') {
                environment {
                    PATH = "${networkDataPath}:${env.PATH}"
                }

                options {
                    timeout(time: 10, unit: 'MINUTES')
                }

                steps {
                    script {
                        String vegacapsuleNodesJSON = vegautils.shellOutput('''vegacapsule nodes ls \
                            --home-path ''' + networkDataPath + '''/testnet''')
                        vegacapsuleNodes = readJSON text: vegacapsuleNodesJSON

                        int upgradeProposalOffset = 100
                        def getLastBlock = { boolean silent ->
                        // We return 0 here because there is a moment when data node is killed for 
                        return vegautils.shellOutput('''devopsscripts vegacapsule last-block \
                            --output value-only \
                            --network-home-path ''' + networkDataPath + '''/testnet \
                            --local || echo 0
                            ''', silent).toInteger()
                        }

                        int initNetworkHeight = getLastBlock(false)
                        int proposalBlock = initNetworkHeight + upgradeProposalOffset
                        int waitForBlock = proposalBlock + 20 // wait for a few blocks after uprade
                        print('Current network heigh is ' + initNetworkHeight)
                        print('Proposing protocol upgrade on block ' + proposalBlock)

                        vegacapsuleNodes.each{nodeName, details -> 
                            if (details.Mode != 'validator') {
                                return
                            }

                            sh('''vega protocol_upgrade_proposal \
                                --home ''' + details.Vega.HomeDir + ''' \
                                --passphrase-file ''' + details.Vega.NodeWalletPassFilePath + ''' \
                                --vega-release-tag ''' + versionToUpgradeNetwork + '''\
                                --height ''' + proposalBlock + ''' \
                                --output json''')
                        }

                        waitUntil(initialRecurrencePeriod: 15000, quiet: true) {
                            int currentNetworkHeight = getLastBlock(true)
                            print('... still waiting, network heigh is ' + currentNetworkHeight)
                            return (currentNetworkHeight >= waitForBlock)
                        }
                        initNetworkHeight = getLastBlock(false)
                        print('Current network heigh is ' + initNetworkHeight)
                    }
                }
            }
        }

        post {
            always {
                script {
                    if (params.CREATE_RELEASE) {
                        withGHCLI('credentialsId': 'github-vega-ci-bot-artifacts') {
                            sh '''gh release delete \
                                --yes \
                                --repo ''' + params.RELEASES_REPO + ''' \
                                v77.7.7-jenkins-visor-pup-''' + currentBuild.number + ''' \
                            | echo "Release does not exist"'''
                        }
                    }

                    dir(networkDataPath) {
                    archiveArtifacts(
                        artifacts: 'testnet/**/*',
                        excludes: [
                        'testnet/**/*.sock',
                        'testnet/data/**/state/data-node/**/*',
                        'testnet/visor/**/vega', // ignore binaries
                        'testnet/visor/**/data-node',
                        ].join(','),
                        allowEmptyArchive: true
                    )
                }

                    slack.slackSendCIStatus(
                        name: 'Visor PUP automatic download binaries pipeline',
                        channel: '#qa-notify',
                        branch: 'st:' + params.SYSTEM_TESTS_BRANCH + ' | vega:' + versionToStartNetwork
                    )
                }
            }
        }
    }
}