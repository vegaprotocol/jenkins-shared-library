/* groovylint-disable DuplicateNumberLiteral, DuplicateStringLiteral, LineLength, NestedBlockDepth */
library (
    identifier: "vega-shared-library@main",
    changelog: false,
)

String latestAvailableRelease
String versionLowerThanLatestAvailableRelease
String networkDataPath
Map vegacapsuleNodes

params = params + [
    RELEASES_REPO: 'vegaprotocol/vega-dev-releases',
    SYSTEM_TESTS_BRANCH: 'visor_pup_tests',
    VEGACAPSULE_BRANCH: 'main',
    VEGATOOLS_BRANCH: 'develop',
    DEVOPSSCRIPTS_BRANCH: 'main',
]

// returns 1 if a > b, 0 if a == b, -1 if a < b
// examples:
// semVerCompare('v0.66.1', 'v0.66.2')
// semVerCompare('1.0.1', 'v0.66.2')
int semVerCompare(String a, String b) {
    // leave only dots and numbers
    semVerA = a.replaceAll(/[^\d\.]/, '')
    semVerB = b.replaceAll(/[^\d\.]/, '')

    List verA = semVerA.tokenize('.')
    List verB = semVerB.tokenize('.')
    int commonIndices = Math.min(verA.size(), verB.size())

    for (int i = 0; i < commonIndices; ++i) {
        int numA = verA[i].toInteger()
        int numB = verB[i].toInteger()

        if (numA > numB) {
            return 1
        } else if (numA < numB) {
            return -1
        }
        continue
    }

    return 0
}

pipeline {
    agent any
    options {
        skipDefaultCheckout true
        timestamps()
        timeout(time: 30, unit: 'MINUTES')
    }

    stages {
        stage('Init') {
            steps {
                cleanWs()
                sh 'printenv'
                echo "params=${params}"
                echo "isPRBuild=${isPRBuild()}"

                script {

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
                    latestAvailableRelease = devRelasesNames[0]
                    lastAvailableReleaseSemVersion = latestAvailableRelease.tokenize('-')[0]

                    // Find first lower version than `latestAvailableRelease` to avoid the `upgrade version is too old` error
                    for (releaseName in devRelasesNames) {
                        String releaseSemVer = releaseName.tokenize('-')[0]
                        // latest version higher than this version
                        if (semVerCompare(lastAvailableReleaseSemVersion, releaseSemVer) > 0) {
                            versionLowerThanLatestAvailableRelease = releaseName
                            break
                        }
                    }

                    echo 'This pipeline will start the network with version ' + versionLowerThanLatestAvailableRelease +
                         ', then perform protocol upgrade to version: ' + latestAvailableRelease
                }
            }
        }

        stage('Pull repositories') {
            steps {
                script {
                    List repositories = [
                        [ name: 'vegaprotocol/vega', branch: versionLowerThanLatestAvailableRelease.tokenize('-')[0] ], // using tag with older version
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
                            ''' + makeAbsBinaryPath + ''' vegacapsule-start-nomad-only '''
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
                        sh 'sudo make vegacapsule-start-network'
                    }
                }
            }
        }

        stage('Propose network upgrade') {
            environment {
                PATH = "${networkDataPath}:${env.PATH}"
            }

            options {
                timeout(time: 10, unit: 'MINUTES')
            }

            steps {
                script {
                    sh 'sleep 720'
                    String vegacapsuleNodesJSON = vegautils.shellOutput('''vegacapsule nodes ls \
                        --home-path ''' + networkDataPath + '''/testnet''')
                    vegacapsuleNodes = readJSON text: vegacapsuleNodesJSON

                    int upgradeProposalOffset = 100
                    def getLastBlock = { boolean silent ->
                    return vegautils.shellOutput('''devopsscripts vegacapsule last-block \
                        --output value-only \
                        --network-home-path ''' + networkDataPath + '''/testnet \
                        --local
                        ''', silent).toInteger()
                    }

                    int initNetworkHeight = getLastBlock(false)
                    int proposalBlock = initNetworkHeight + upgradeProposalOffset
                    print('Current network heigh is ' + initNetworkHeight)
                    print('Proposing protocol upgrade on block ' + proposalBlock)

                    vegacapsuleNodes.each{nodeName, details -> 
                        if (detauls.Mode != "validator") {
                            return
                        }

                        sh('''vega protocol_upgrade_proposal \
                            --home ''' + details.Vega.HomeDir + ''' \
                             --passphrase-file ''' + details.Vega.NodeWalletPassFilePath + ''' \
                             --vega-release-tag ''' + latestAvailableRelease + '''\
                             --height ''' + proposalBlock)

                        
                        sh 'sleep 360'
                    }
                }
            }
        }
    }
}
