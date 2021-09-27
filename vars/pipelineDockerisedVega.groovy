/* groovylint-disable DuplicateStringLiteral */
/* groovylint-disable DuplicateNumberLiteral */
/* groovylint-disable NestedBlockDepth */
/* groovylint-disable MethodSize */

void call(Map config) {
    //
    // Parse input arguments
    //
    List inputParameters = config.get('parameters', [])
    List inputGitRepos = config.get('git', [])
    Map<String,Closure> inputPrepareStages = config.get('prepareStages', [:])
    Closure inputMainStage = config.get('mainStage', null)
    Closure inputPost = config.get('post', null)

    //
    // Setup PARAMETERS
    //
    setupJobParameters(inputParameters)


    node(params.JENKINS_AGENT_LABEL) {
        skipDefaultCheckout()
        cleanWs()
        //
        // Local variables
        //
        String buildShortName = env.JOB_BASE_NAME.replaceAll('[^A-Za-z0-9\\._]', '-')
        String dockerisedVegaPrefix = "dv-${buildShortName}-${env.BUILD_NUMBER}-${env.EXECUTOR_NUMBER}"
        Map vars = [
            params: params,
            dockerCredentials: [credentialsId: 'github-vega-ci-bot-artifacts',
                                          url: 'https://docker.pkg.github.com'],
            portbase: (env.EXECUTOR_NUMBER as int) * 1000 + 1000,
            buildShortName: buildShortName,
            dockerisedVegaPrefix: dockerisedVegaPrefix,
            dockerisedVegaBasedir: "${env.WORKSPACE}/dockerisedvega-home",
            dockerImageVegaCore: "docker.pkg.github.com/vegaprotocol/vega/vega:${dockerisedVegaPrefix}",
            dockerImageDataNode: "docker.pkg.github.com/vegaprotocol/data-node/data-node:${dockerisedVegaPrefix}",
            dockerImageGoWallet: "vegaprotocol/go-wallet:${dockerisedVegaPrefix}",
            dockerImageVegatools: "vegaprotocol/vegatools:${dockerisedVegaPrefix}",
            jenkinsAgentPublicIP: null
        ]

        timestamps {
            try {
                timeout(time: params.TIMEOUT as int, unit: 'MINUTES') {
                    stage('Config') {
                        // Printout all configuration variables
                        vars.jenkinsAgentPublicIP = sh(
                            script: 'curl http://169.254.169.254/latest/meta-data/public-ipv4',
                            returnStdout: true,
                        ).trim()
                        sh 'printenv'
                        echo "params=${params.inspect()}"
                        echo "vars=${vars.inspect()}"
                    }
                    stage('Git Clone') {
                        // git clone all required repositories
                        // and repositories provided by function caller
                        gitClone(params, inputGitRepos)
                    }
                    stage('Prepare') {
                        // run various preparation steps for dockerised vega
                        // and run preparation stages provided by functon caller
                        prepareEverything(
                            inputPrepareStages,
                            [   dockerImageVegaCore: vars.dockerImageVegaCore,
                                dockerImageDataNode: vars.dockerImageDataNode,
                                dockerImageGoWallet: vars.dockerImageGoWallet,
                                dockerImageVegatools: vars.dockerImageVegatools
                            ],
                            vars.dockerisedVegaBasedir,
                            vars.dockerCredentials
                        )
                    }
                    stage('Start Dockerised Vega') {
                        startDockerisedVega(
                            params,
                            vars.dockerisedVegaBasedir,
                            vars.dockerisedVegaPrefix,
                            vars.portbase,
                            vars.dockerCredentials
                        )
                    }
                    stage(' ') {
                        // start stages provided by function caller
                        // and in parallel stages: log tails of all the containers
                        runMainStages(inputMainStage, vars.dockerisedVegaPrefix, vars)
                    }
                }
            } finally {
                stage('Cleanup') {
                    if (inputPost) {
                        retry(3) {
                            // Run finally block provided by function caller
                            inputPost(vars)
                        }
                    }
                    retry(3) {
                        stopDockerisedVega(vars.dockerisedVegaPrefix, vars.portbase)
                    }
                    /*retry(3) {
                        removeDockerImages([
                            vars.dockerImageVegaCore,
                            vars.dockerImageDataNode,
                            vars.dockerImageGoWallet,
                            vars.dockerImageVegatools
                        ])
                    }*/
                }
            }
        }
    }
}


void setupJobParameters(List inputParameters) {
    List dockerisedVegaParameters = [
        /* Branches */
        string(
            name: 'VEGA_CORE_BRANCH', defaultValue: pipelineDefaults.dv.vegaCoreBranch,
            description: 'Git branch name of the vegaprotocol/vega repository'),
        string(
            name: 'DATA_NODE_BRANCH', defaultValue: pipelineDefaults.dv.dataNodeBranch,
            description: 'Git branch name of the vegaprotocol/data-node repository'),
        string(
            name: 'GO_WALLET_BRANCH', defaultValue: pipelineDefaults.dv.goWalletBranch,
            description: 'Git branch name of the vegaprotocol/go-wallet repository'),
        string(
            name: 'DEVOPS_INFRA_BRANCH', defaultValue: pipelineDefaults.dv.devopsInfraBranch,
            description: 'Git branch name of the vegaprotocol/devops-infra repository'),
        string(
            name: 'VEGATOOLS_BRANCH', defaultValue: pipelineDefaults.dv.vegatoolsBranch,
            description: 'Git branch name of the vegaprotocol/vegatools repository'),
        /* Dockerised Vega Config */
        string(
            name: 'DV_VALIDATOR_NODE_COUNT', defaultValue: pipelineDefaults.dv.validatorNodeCount,
            description: 'Number of validator nodes'),
        string(
            name: 'DV_NON_VALIDATOR_NODE_COUNT', defaultValue: pipelineDefaults.dv.nonValidatorNodeCount,
            description: 'Number of non-validator nodes and data-nodes'),
        /* Vega Network Config */
        text(
            name: 'DV_GENESIS_JSON', defaultValue: pipelineDefaults.dv.genesisJSON,
            description: 'Tendermint genesis overrides in JSON format'),
        text(
            name: 'DV_PROPOSALS_JSON', defaultValue: pipelineDefaults.dv.proposalsJSON,
            description: 'Submit proposals, vote on them, wait for enactment. JSON format'),
        /* Debug options */
        string(
            name: 'DV_TENDERMINT_LOG_LEVEL', defaultValue: pipelineDefaults.dv.tendermintLogLevel,
            description: 'The Tendermint log level (debug, info, error)'),
        string(
            name: 'DV_VEGA_CORE_LOG_LEVEL', defaultValue: pipelineDefaults.dv.vegaCoreLogLevel,
            description: ' The Vega core log level (Debug, Info, Warning, Error)'),
        booleanParam(
            name: 'DV_VEGA_CORE_DLV', defaultValue: pipelineDefaults.dv.vegaCoreDLV,
            description: 'Run vega nodes with dlv'),
        /* Pipeline Config */
        string(
            name: 'JENKINS_AGENT_LABEL', defaultValue: pipelineDefaults.dv.agent,
            description: 'Specify Jenkins machine on which to run this pipeline'),
        string(
            name: 'TIMEOUT', defaultValue: pipelineDefaults.dv.timeout,
            description: 'Number of minutes after which Pipeline will be force-stopped'),
    ]

    List paramNames = []
    List jobParameters = []

    // manually remove duplicate parameters
    for (def param : inputParameters + dockerisedVegaParameters) {
        String name = param.arguments.name
        if (!(name in paramNames)) {
            paramNames += name
            jobParameters += param
        }
    }

    echo "params before=${params}"

    properties([parameters(jobParameters)])

    echo "params=${params}"
}

void gitClone(Map params, List<Map> inputGitRepos) {
    List<Map> gitRepos = inputGitRepos.clone()
    gitRepos.addAll([
        [   name: 'vega',
            branch: params.VEGA_CORE_BRANCH,  // can be Job/Pipeline parameter name or branch name directly
            // url: 'git@github.com:vegaprotocol/vega.git',
            // - Optional, default: "git@github.com:vegaprotocol/${name}.git"
            // dir: 'vega', - Optional, default: "${name}"
        ],
        [   name: 'data-node',
            branch: params.DATA_NODE_BRANCH,
        ],
        [   name: 'go-wallet',
            branch: params.GO_WALLET_BRANCH,
        ],
        [   name: 'vegatools',
            branch: params.VEGATOOLS_BRANCH,
        ],
        [   name: 'devops-infra',
            branch: params.DEVOPS_INFRA_BRANCH,
        ],
    ])

    Map<String,Closure> concurrentStages = [:]
    for (Map repo : gitRepos) {
        String repoName = repo.name
        String repoBranch = params.get(repo.branch) ?: repo.branch
        String repoURL = repo.get('url', "git@github.com:vegaprotocol/${repoName}.git")
        String localDir = repo.get('dir', repoName)
        concurrentStages[repoName] = {
            stage(repoName) {
                retry(3) {
                    dir(localDir) {
                        git branch: repoBranch,
                            credentialsId: 'vega-ci-bot',
                            url: repoURL
                    }
                }
            }
        }
    }

    parallel(concurrentStages)
}


void prepareEverything(
    Map<String,Closure> inputPrepareStages,
    Map<String,String> dockerImages,
    String dockerisedVegaBasedir,
    Map<String,String> dockerCredentials
) {
    Map<String,Closure> concurrentStages = [:]

    concurrentStages << getPrepareVegaCoreStages(dockerImages.dockerImageVegaCore, dockerCredentials)
    concurrentStages << getPrepareDataNodeStages(dockerImages.dockerImageDataNode, dockerCredentials)
    concurrentStages << getPrepareGoWalletStages(dockerImages.dockerImageGoWallet, dockerCredentials)
    concurrentStages << getPrepareVegatoolsStages(dockerImages.dockerImageVegatools, dockerCredentials)
    concurrentStages << getPrepareDockerisedVegaStages(dockerisedVegaBasedir, dockerCredentials)

    concurrentStages << inputPrepareStages

    withEnv([
        'CGO_ENABLED=0',
        'GO111MODULE=on',
        'GOOS=linux',
        'GOARCH=amd64',
    ]) {
        parallel(concurrentStages)
    }
}

//
// Prepare Vega Core
//
Map<String,Closure> getPrepareVegaCoreStages(
    String vegaCoreDockerImage,
    Map<String,String> dockerCredentials) {
    return ['v-core': {
        stage('Compile Vega Core') {
            retry(3) {
                dir('vega') {
                    sh label: 'Compile', script: '''
                        go build -v -o ./cmd/vega/vega-linux-amd64 ./cmd/vega
                    '''
                    sh label: 'Sanity check', script: '''
                        file ./cmd/vega/vega-linux-amd64
                        ./cmd/vega/vega-linux-amd64 version
                    '''
                }
            }
        }
        stage('Build Vega Core Docker Image') {
            retry(3) {
                dir('vega') {
                    withDockerRegistry(dockerCredentials) {
                        sh label: 'docker build', script: """#!/bin/bash -e
                            rm -rf ./docker/bin
                            mkdir -p ./docker/bin
                            cp ./cmd/vega/vega-linux-amd64 ./docker/bin/vega
                            docker build --pull -t "${vegaCoreDockerImage}" ./docker
                        """
                    }
                    sh label: 'sanity check',
                        script: "docker run --rm --entrypoint 'vega' '${vegaCoreDockerImage}' version"
                }
            }
        }
    }]
}


//
// Prepare Data-Node
//
Map<String,Closure> getPrepareDataNodeStages(
    String dataNodeDockerImage,
    Map<String,String> dockerCredentials) {
    return ['d-node': {
        stage('Compile Data-Node') {
            retry(3) {
                dir('data-node') {
                    sh label: 'Compile', script: '''
                        go build -o ./cmd/data-node/data-node-linux-amd64 ./cmd/data-node
                    '''
                    sh label: 'Sanity check', script: '''
                        file ./cmd/data-node/data-node-linux-amd64
                        ./cmd/data-node/data-node-linux-amd64 version
                    '''
                }
            }
        }
        stage('Build Data-Node Docker Image') {
            retry(3) {
                dir('data-node') {
                    withDockerRegistry(dockerCredentials) {
                        sh label: 'docker build', script: """#!/bin/bash -e
                            rm -rf ./docker/bin
                            mkdir -p ./docker/bin
                            cp ./cmd/data-node/data-node-linux-amd64 ./docker/bin/data-node
                            docker build --pull -t "${dataNodeDockerImage}" ./docker
                        """
                    }
                    sh label: 'Sanity check',
                        script: "docker run --rm '${dataNodeDockerImage}' version"
                }
            }
        }
    }]
}

//
// Prepare Go-Wallet
//
Map<String,Closure> getPrepareGoWalletStages(
    String goWalletDockerImage,
    Map<String,String> dockerCredentials) {
    return ['wallet': {
        stage('Compile Go-Wallet') {
            retry(3) {
                dir('go-wallet') {
                    sh label: 'Compile', script: '''
                        go build -o ./build/vegawallet-linux-amd64
                    '''
                    sh label: 'Sanity check', script: '''
                        file ./build/vegawallet-linux-amd64
                        ./build/vegawallet-linux-amd64 version --output json
                    '''
                }
            }
        }
        stage('Build Go-Wallet Docker Image') {
            retry(3) {
                dir('go-wallet') {
                    withDockerRegistry(dockerCredentials) {
                        sh label: 'docker build', script: """#!/bin/bash -e
                            docker build --pull -t "${goWalletDockerImage}" .
                        """
                    }
                    sh label: 'Sanity check',
                        script: "docker run --rm '${goWalletDockerImage}' version --output json"
                }
            }
        }
    }]
}

//
// Prepare Vegatools
//
Map<String,Closure> getPrepareVegatoolsStages(
    String vegatoolsDockerImage,
    Map<String,String> dockerCredentials) {
    return ['vtools': {
        stage('Compile Vegatools') {
            retry(3) {
                dir('vegatools') {
                    sh label: 'Compile', script: '''
                        go build -o ./build/vegatools-linux-amd64
                    '''
                    sh label: 'Sanity check', script: '''
                        file ./build/vegatools-linux-amd64
                        ./build/vegatools-linux-amd64 --help
                    '''
                }
            }
        }
        stage('Build Vegatools Docker Image') {
            retry(3) {
                dir('vegatools') {
                    withDockerRegistry(dockerCredentials) {
                        sh label: 'docker build', script: """#!/bin/bash -e
                            |./build/vegatools-linux-amd64
                            |docker build --pull -t "${vegatoolsDockerImage}" -f - . << EOF
                                |FROM alpine:3.14 as alpine
                                |RUN apk add -U --no-cache ca-certificates
                                |FROM scratch
                                |COPY --from=alpine /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/
                                |ADD ./build/vegatools-linux-amd64 /usr/local/bin/vegatools
                                |ENTRYPOINT ["/usr/local/bin/vegatools"]
                            |EOF
                        """.stripMargin()
                    }
                    sh label: 'Sanity check',
                        script: "docker run --rm '${vegatoolsDockerImage}' --help"
                }
            }
        }
    }]
}

//
// Prepare Dockerised Vega
//
Map<String,Closure> getPrepareDockerisedVegaStages(
    String dockerisedVegaBasedir,
    Map<String,String> dockerCredentials
) {
    return ['dv': {
        stage('Setup') {
            sh label: 'Create dockerised vega basedir',
                script: "mkdir -p ${dockerisedVegaBasedir}"
        }
        stage('Docker Pull') {
            retry(3) {
                dir('devops-infra/scripts') {
                    withDockerRegistry(dockerCredentials) {
                        sh './dockerisedvega.sh pull'
                    }
                }
            }
        }
    }]
}

//
// Starts Dockerised Vega
//
void startDockerisedVega(
    Map params,
    String dockerisedVegaBasedir,
    String dockerisedVegaPrefix,
    Integer portbase,
    Map<String,String> dockerCredentials
) {
    sh 'printenv'
    echo "params=${params.inspect()}"
    retry(2) {
        timeout(time: 5, unit: 'MINUTES') {
            String genesisJSON = params.get('DV_GENESIS_JSON', null)
            String marketProposalsJSON = params.get('DV_PROPOSALS_JSON', null)
            boolean dlvEnabled = params.get('DV_VEGA_CORE_DLV', false)
            int validatorNodeCount = params.DV_VALIDATOR_NODE_COUNT as int
            int nonValidatorNodeCount = params.DV_NON_VALIDATOR_NODE_COUNT as int
            String tendermintLogLevel = params.DV_TENDERMINT_LOG_LEVEL
            String vegaCoreLogLevel = params.DV_VEGA_CORE_LOG_LEVEL

            String startupArguments = ''

            if (genesisJSON) {
                String genesisFile = "/tmp/genesis-${dockerisedVegaPrefix}.json"
                echo "genesisJSON=${genesisJSON}"
                if (fileExists(genesisJSON.trim())) {
                    sh label: 'copy genesis file', script: """#!/bin/bash -e
                        cp "${genesisJSON}" "${genesisFile}"
                    """
                } else {
                    sh label: 'create genesis file', script: """#!/bin/bash -e
                        echo "${genesisJSON}" > "${genesisFile}"
                    """
                }
                startupArguments += " --genesis ${genesisFile}"
                sh label: 'content of a custom genesis file', script: """#!/bin/bash -e
                    echo "Content of ${genesisFile}"
                    echo "----------------"
                    cat "${genesisFile}"
                    echo "----------------"
                """
            }
            if (marketProposalsJSON) {
                String proposalsFile = "/tmp/proposals-${dockerisedVegaPrefix}.json"
                sh label: 'create market proposals file', script: """#!/bin/bash -e
                    echo "${marketProposalsJSON}" > "${proposalsFile}"
                """
                startupArguments += " --proposals ${proposalsFile}"
                sh label: 'content of a proposals file', script: """#!/bin/bash -e
                    echo "Content of ${proposalsFile}:"
                    echo "----------------"
                    cat "${proposalsFile}"
                    echo "----------------"
                """
            }
            if (dlvEnabled) {
                startupArguments += ' --dlv'
            }
            dir('devops-infra/scripts') {
                sh label: 'make sure dockerised-vega is not running', script: """#!/bin/bash -e
                    ./dockerisedvega.sh --prefix '${dockerisedVegaPrefix}' --portbase '${portbase}' stop
                """
                withDockerRegistry(dockerCredentials) {
                    sh label: 'start dockerised-vega', script: """#!/bin/bash -e
                        ./dockerisedvega.sh \
                            --datadir "${dockerisedVegaBasedir}" \
                            --prefix "${dockerisedVegaPrefix}" \
                            --portbase "${portbase}" \
                            --validators "${validatorNodeCount}" \
                            --nonvalidators "${nonValidatorNodeCount}" \
                            --vega-version "${dockerisedVegaPrefix}" \
                            --datanode-version "${dockerisedVegaPrefix}" \
                            --vegawallet-version "${dockerisedVegaPrefix}" \
                            --tendermint-loglevel "${tendermintLogLevel}" \
                            --vega-loglevel "${vegaCoreLogLevel}" \
                            ${startupArguments} \
                            start
                    """
                }
            }
            sh label: 'list all the containers', script: """#!/bin/bash -e
                docker ps -a --filter "name=${dockerisedVegaPrefix}"
            """
        }
    }
}

//
// Starts Dockerised Vega
//
void stopDockerisedVega(
    String dockerisedVegaPrefix,
    Integer portbase
) {
    dir('devops-infra/scripts') {
        sh label: 'stop dockerised-vega', script: """#!/bin/bash -e
            ./dockerisedvega.sh --prefix '${dockerisedVegaPrefix}' --portbase '${portbase}' stop
        """
    }
}

void removeDockerImages(List<String> dockerImages) {
    for (String image : dockerImages) {
        sh label: "Remove docker image ${image}", script: """#!/bin/bash -e
            [ -z "\$(docker images -q "${image}")" ] || docker rmi "${image}"
        """
    }
}

List<String> getDockerContainerNames(String nameWildcard) {
    return sh(
            script: "docker ps -a --filter 'name=${nameWildcard}' --format '{{.Names}}'",
            returnStdout: true,
        ).trim().split('\n')
}

void runMainStages(Closure inputMainStage, String dockerisedVegaPrefix, Map vars) {
    Map<String,Closure> concurrentStages = [:]

    String dockerLogsCommand = 'docker logs -t -f'

    // Start passed in stages
    concurrentStages[' main'] = {
        try {
            if (inputMainStage) {
                inputMainStage(vars)
            }
        } finally {
            // wait for logs to flush properly
            sleep(time:3, unit:'SECONDS')
            // stop all docker log tails from other concurrent stages
            retry(3) {
                sh label: 'Stop tailing logs', script: """#!/bin/bash -e
                pkill -f "${dockerLogsCommand} ${dockerisedVegaPrefix}"
                """
            }
        }
    }

    // Start tailing logs in all containers
    for (String containerName : getDockerContainerNames(dockerisedVegaPrefix)) {
        String longName = containerName
        String shortName = longName - "${dockerisedVegaPrefix}-"
        concurrentStages[shortName] = {
            stage(shortName) {
                sh label: "Logs from ${shortName}",
                    returnStatus: true,  // ignore exit code
                    script: """#!/bin/bash -e
                    ${dockerLogsCommand} ${longName}
                    """
            }
        }
    }

    parallel(concurrentStages)
}
