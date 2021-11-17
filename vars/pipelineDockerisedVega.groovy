/* groovylint-disable DuplicateStringLiteral */
/* groovylint-disable DuplicateNumberLiteral */
/* groovylint-disable NestedBlockDepth */
/* groovylint-disable MethodSize */
import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException
import org.jenkinsci.plugins.pipeline.modeldefinition.Utils

import io.vegaprotocol.DockerisedVega

void call(Map config=[:]) {
    //
    // Parse input arguments
    //
    List inputParameters = config.get('parameters', [])
    List inputGitRepos = config.get('git', [])
    Map<String,Closure> inputPrepareStages = config.get('prepareStages', [:])
    Closure inputMainStage = config.get('mainStage', null)
    Closure inputAfterCheckpointRestoreStage = config.get('afterCheckpointRestoreStage', null)
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

        DockerisedVega dockerisedVega = getDockerisedVega(
            prefix: dockerisedVegaPrefix,
            portbase: (env.EXECUTOR_NUMBER as int) * 1000 + 1000,
            basedir: "${env.WORKSPACE}/dockerisedvega-home",
            dockerisedvagaScript: "${env.WORKSPACE}/devops-infra/scripts/dockerisedvega.sh",
            validators: params.DV_VALIDATOR_NODE_COUNT as int,
            nonValidators: params.DV_NON_VALIDATOR_NODE_COUNT as int,
            genesisFile: params.DV_GENESIS_JSON,
            marketProposalsFile: params.DV_PROPOSALS_JSON,
            dlv: params.DV_VEGA_CORE_DLV,
            vegaCoreVersion: params.VEGA_CORE_BRANCH ? dockerisedVegaPrefix : null,
            dataNodeVersion: params.DATA_NODE_BRANCH ? dockerisedVegaPrefix : null,
            vegaWalletVersion: params.VEGAWALLET_BRANCH ? dockerisedVegaPrefix : null,
            ethereumEventForwarderVersion: params.ETHEREUM_EVENT_FORWARDER_BRANCH ? dockerisedVegaPrefix : null,
            vegatoolsScript: "${env.WORKSPACE}/vegatools/build/vegatools-linux-amd64",
            tendermintLogLevel: params.DV_TENDERMINT_LOG_LEVEL,
            vegaCoreLogLevel: params.DV_VEGA_CORE_LOG_LEVEL,
        )

        // vars are passed as argument to input closoures: input[X]Stages
        Map vars = [
            params: params,
            dockerCredentials: [credentialsId: 'github-vega-ci-bot-artifacts',
                                          url: 'https://docker.pkg.github.com'],
            dockerisedVega: dockerisedVega,
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
                        echo "${dockerisedVega}"
                    }
                    stage('Git Clone') {
                        // git clone all required repositories
                        // and repositories provided by function caller
                        gitClone(params, inputGitRepos)
                    }
                    stage('Prepare') {
                        // run various preparation steps for dockerised vega
                        // and run preparation stages provided by functon caller
                        prepareEverything(inputPrepareStages, dockerisedVega, vars.dockerCredentials, vars)
                    }
                    if (inputMainStage) {
                        stage('Start Dockerised Vega') {
                            retry(2) {
                                try {
                                    timeout(time: 5, unit: 'MINUTES') {
                                        dockerisedVega.stop()
                                        withDockerRegistry(vars.dockerCredentials) {
                                            dockerisedVega.start()
                                        }
                                    }
                                } catch (err) {
                                    dockerisedVega.printAllContainers()
                                    dockerisedVega.printAllLogs()
                                    error("dockerised-vega failed to start: ${err.message}")
                                }
                            }
                        }
                        stage('Store genesis file') {
                            dockerisedVega.saveGenesisToFile(pipelineDefaults.art.genesis)
                            archiveArtifacts artifacts: pipelineDefaults.art.genesis,
                                allowEmptyArchive: true,
                                fingerprint: true
                        }
                        stage(' ') {
                            // start stages provided by function caller
                            // and in parallel stages: log tails of all the containers
                            runMainStages(inputMainStage, dockerisedVega, vars)
                        }
                    }
                    if (inputMainStage && inputAfterCheckpointRestoreStage) {
                        stage('Wait for checkpoint') {
                            echo 'Waiting up to 2 min for next checkpoint'
                            timeout(time: 2, unit: 'MINUTES') {
                                dockerisedVega.waitForNextCheckpoint()
                            }
                        }
                        stage('Stop Vega network only') {
                            retry(3) {
                                dockerisedVega.stop(resume: true)
                            }
                            dockerisedVega.printAllContainers()
                        }
                        stage('Store latest checkpoint') {
                            dockerisedVega.saveLatestCheckpointToFile(pipelineDefaults.art.lnl.checkpointRestore)
                            archiveArtifacts artifacts: pipelineDefaults.art.lnl.checkpointRestore,
                                allowEmptyArchive: true,
                                fingerprint: true
                        }
                    }
                    if (inputAfterCheckpointRestoreStage) {
                        stage('Restore Vega network from Checkpoint') {
                            retry(2) {
                                timeout(time: 5, unit: 'MINUTES') {
                                    withDockerRegistry(vars.dockerCredentials) {
                                        dockerisedVega.start(resume: true)
                                    }
                                }
                            }
                        }
                        stage('Store genesis file') {
                            dockerisedVega.saveGenesisToFile(pipelineDefaults.art.genesisRestore)
                            archiveArtifacts artifacts: pipelineDefaults.art.genesisRestore,
                                allowEmptyArchive: true,
                                fingerprint: true
                        }
                        stage(' ') {
                            // start stages provided by function caller
                            // and in parallel stages: log tails of all the containers
                            runMainStages(inputAfterCheckpointRestoreStage, dockerisedVega, vars)
                        }
                    }
                }
                // Workaround Jenkins problem: https://issues.jenkins.io/browse/JENKINS-47403
                // i.e. `currentResult` is not set properly in the finally block
                // CloudBees workaround: https://support.cloudbees.com/hc/en-us/articles/218554077-how-to-set-current-build-result-in-pipeline
                currentBuild.result = currentBuild.result ?: 'SUCCESS'
                // result can be SUCCESS or UNSTABLE
            } catch (FlowInterruptedException e) {
                currentBuild.result = 'ABORTED'
                throw e
            } catch (e) {
                // Workaround Jenkins problem: https://issues.jenkins.io/browse/JENKINS-47403
                // i.e. `currentResult` is not set properly in the finally block
                // CloudBees workaround: https://support.cloudbees.com/hc/en-us/articles/218554077-how-to-set-current-build-result-in-pipeline
                currentBuild.result = 'FAILURE'
                throw e
            } finally {
                stage('Cleanup') {
                    try {
                        if (dockerisedVega.isNodeRunning()) {
                            echo 'Waiting up to 2 min for next checkpoint'
                            timeout(time: 2, unit: 'MINUTES') {
                                dockerisedVega.waitForNextCheckpoint()
                            }
                        } else {
                            echo 'Skip waiting for next checkpoint - no node is running'
                        }
                        retry(3) {
                            dockerisedVega.stop()
                        }
                        String artifactLastCheckpoint = pipelineDefaults.art.checkpointEnd
                        if (inputAfterCheckpointRestoreStage) {
                            artifactLastCheckpoint = pipelineDefaults.art.lnl.checkpointEnd
                        }
                        if (dockerisedVega.getLatestCheckpointFilepath()) {
                            dockerisedVega.saveLatestCheckpointToFile(artifactLastCheckpoint)
                            archiveArtifacts artifacts: artifactLastCheckpoint,
                                allowEmptyArchive: true,
                                fingerprint: true
                        } else {
                            echo 'Skip archiving last checkpoint - no checkpoint available'
                        }
                        /*retry(3) {
                            removeDockerImages([
                                vars.dockerImageVegaCore,
                                vars.dockerImageDataNode,
                                vars.dockerImageVegaWallet
                            ])
                        }*/
                    } catch (e) {
                        // Workaround Jenkins problem: https://issues.jenkins.io/browse/JENKINS-47403
                        // i.e. `currentResult` is not set properly in the finally block
                        // CloudBees workaround: https://support.cloudbees.com/hc/en-us/articles/218554077-how-to-set-current-build-result-in-pipeline
                        currentBuild.result = 'FAILURE'
                        throw e
                    } finally {
                        if (inputPost) {
                            retry(3) {
                                // Run finally block provided by function caller
                                inputPost(vars)
                            }
                        }
                    }
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
            description: '''Git branch, tag or hash of the vegaprotocol/vega repository.
            e.g. "develop", "v0.44.0 or commit hash. Default empty: use latests published version.'''),
        string(
            name: 'DATA_NODE_BRANCH', defaultValue: pipelineDefaults.dv.dataNodeBranch,
            description: '''Git branch, tag or hash of the vegaprotocol/data-node repository.
            e.g. "develop", "v0.44.0" or commit hash. Default empty: use latests published version'''),
        string(
            name: 'VEGAWALLET_BRANCH', defaultValue: pipelineDefaults.dv.vegaWalletBranch,
            description: '''Git branch, tag or hash of the vegaprotocol/vegawallet repository.
            e.g. "develop", "v0.9.0" or commit hash. Default empty: use latest published version.'''),
        string(
            name: 'ETHEREUM_EVENT_FORWARDER_BRANCH', defaultValue: pipelineDefaults.dv.ethereumEventForwarderBranch,
            description: '''Git branch, tag or hash of the vegaprotocol/ethereum-event-forwarder repository.
            e.g. "main", "v0.44.0" or commit hash. Default empty: use latest published version.'''),
        string(
            name: 'DEVOPS_INFRA_BRANCH', defaultValue: pipelineDefaults.dv.devopsInfraBranch,
            description: 'Git branch, tag or hash of the vegaprotocol/devops-infra repository'),
        string(
            name: 'VEGATOOLS_BRANCH', defaultValue: pipelineDefaults.dv.vegatoolsBranch,
            description: 'Git branch, tag or hash of the vegaprotocol/vegatools repository'),
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

    properties([copyArtifactPermission('*'), parameters(jobParameters)])

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
        [   name: 'vegawallet',
            branch: params.VEGAWALLET_BRANCH,
        ],
        [   name: 'ethereum-event-forwarder',
            branch: params.ETHEREUM_EVENT_FORWARDER_BRANCH,
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
                if (repoBranch == '' ) {
                    echo "Skip git clone: empty branch for ${repoName}."
                    Utils.markStageSkippedForConditional(repoName)
                } else {
                    retry(3) {
                        dir(localDir) {
                            checkout([
                                $class: 'GitSCM',
                                branches: [[name: repoBranch]],
                                userRemoteConfigs: [[url: repoURL, credentialsId: 'vega-ci-bot']]])
                        }
                    }
                }
            }
        }
    }

    parallel(concurrentStages)
}


void prepareEverything(
    Map<String,Closure> inputPrepareStages,
    DockerisedVega dockerisedVega,
    Map<String,String> dockerCredentials,
    Map vars
) {
    Map<String,Closure> concurrentStages = [:]

    concurrentStages << getPrepareVegaCoreStages(dockerisedVega.dockerImageVegaCore, dockerCredentials)
    concurrentStages << getPrepareDataNodeStages(dockerisedVega.dockerImageDataNode, dockerCredentials)
    concurrentStages << getPreparevegaWalletStages(dockerisedVega.dockerImageVegaWallet, dockerCredentials)
    concurrentStages << getPrepareEthereumEventForwarderStages(
                            dockerisedVega.dockerImageEthereumEventForwarder, dockerCredentials)
    concurrentStages << getPrepareVegatoolsStages()
    concurrentStages << getPrepareDockerisedVegaStages(dockerisedVega, dockerCredentials)

    concurrentStages << inputPrepareStages.collectEntries { name, c ->
        [name, {
            c(vars)
        }]
    }

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
            if (fileExists('vega')) {
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
            } else {
                echo "Skip Compile Vega Core: no directory 'vega' with source code."
                Utils.markStageSkippedForConditional('Compile Vega Core')
            }
        }
        stage('Build Vega Core Docker Image') {
            if (fileExists('vega')) {
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
            } else {
                echo "Skip Build Vega Core Docker Image: no directory 'vega' with source code."
                Utils.markStageSkippedForConditional('Build Vega Core Docker Image')
            }
        }
        stage('Pull latest Vega Core Docker Image') {
            if (fileExists('data-node')) {
                echo "Skip Pull latest Vega Core Docker Image: directory 'vega' with source code exists."
                Utils.markStageSkippedForConditional('Pull latest Vega Core Docker Image')
            } else {
                withDockerRegistry(dockerCredentials) {
                    sh label: 'Pull latest Vega Core Docker Image', script: """#!/bin/bash -e
                        docker pull docker.pkg.github.com/vegaprotocol/vega/vega:develop
                    """
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
            if (fileExists('data-node')) {
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
            } else {
                echo "Skip Compile Data-Node: no directory 'data-node' with source code."
                Utils.markStageSkippedForConditional('Compile Data-Node')
            }
        }
        stage('Build Data-Node Docker Image') {
            if (fileExists('data-node')) {
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
            } else {
                echo "Skip Build Data-Node Docker Image: no directory 'data-node' with source code."
                Utils.markStageSkippedForConditional('Build Data-Node Docker Image')
            }
        }
        stage('Pull latest Data-Node Docker Image') {
            if (fileExists('data-node')) {
                echo "Skip Pull latest Data-Node Docker Image: directory 'data-node' with source code exists."
                Utils.markStageSkippedForConditional('Pull latest Data-Node Docker Image')
            } else {
                withDockerRegistry(dockerCredentials) {
                    sh label: 'ull latest Data-Node Docker Image', script: """#!/bin/bash -e
                        docker pull docker.pkg.github.com/vegaprotocol/data-node/data-node:edge
                    """
                }
            }
        }
    }]
}

//
// Prepare vegawallet
//
Map<String,Closure> getPreparevegaWalletStages(
    String vegaWalletDockerImage,
    Map<String,String> dockerCredentials) {
    return ['wallet': {
        stage('Compile vegawallet') {
            if (fileExists('vegawallet')) {
                retry(3) {
                    dir('vegawallet') {
                        sh label: 'Compile', script: '''
                            go build -o ./build/vegawallet-linux-amd64
                        '''
                        sh label: 'Sanity check', script: '''
                            file ./build/vegawallet-linux-amd64
                            ./build/vegawallet-linux-amd64 version --output json
                        '''
                    }
                }
            } else {
                echo "Skip Compile vegawallet: no directory 'vegawallet' with source code."
                Utils.markStageSkippedForConditional('Compile vegawallet')
            }
        }
        stage('Build vegawallet Docker Image') {
            if (fileExists('vegawallet')) {
                retry(3) {
                    dir('vegawallet') {
                        withDockerRegistry(dockerCredentials) {
                            sh label: 'docker build', script: """#!/bin/bash -e
                                docker build --pull -t "${vegaWalletDockerImage}" .
                            """
                        }
                        sh label: 'Sanity check',
                            script: "docker run --rm '${vegaWalletDockerImage}' version --output json"
                    }
                }
            } else {
                echo "Skip Build vegawallet Docker Image: no directory 'vegawallet' with source code."
                Utils.markStageSkippedForConditional('Build vegawallet Docker Image')
            }
        }
        stage('Pull latest vegawallet Docker Image') {
            if (fileExists('vegawallet')) {
                echo "Skip Pull latest vegawallet Docker Image: directory 'vegawallet' with source code exists."
                Utils.markStageSkippedForConditional('Pull latest vegawallet Docker Image')
            } else {
                withDockerRegistry(dockerCredentials) {
                    sh label: 'Pull latest vegawallet Docker Image', script: """#!/bin/bash -e
                        docker pull vegaprotocol/vegawallet:latest
                    """
                }
            }
        }
    }]
}

//
// Prepare Ethereum Event Forwarder
//
Map<String,Closure> getPrepareEthereumEventForwarderStages(
    String ethereumEventForwarderDockerImage,
    Map<String,String> dockerCredentials) {
    return ['eef': {
        stage('Build Ethereum Event Forwarder Docker Image') {
            if (fileExists('ethereum-event-forwarder')) {
                retry(3) {
                    dir('ethereum-event-forwarder') {
                        withDockerRegistry(dockerCredentials) {
                            sh label: 'docker build', script: """#!/bin/bash -e
                                docker build --pull -t "${ethereumEventForwarderDockerImage}" .
                            """
                        }
                    }
                }
            } else {
                echo "Skip Build Ethereum Event Forwarder Docker Image: no directory 'ethereum-event-forwarder' with source code."
                Utils.markStageSkippedForConditional('Build Ethereum Event Forwarder Docker Image')

            }
        }
        stage('Pull latest Ethereum Event Forwarder Docker Image') {
            if (fileExists('ethereum-event-forwarder')) {
                echo "Skip Pull latest Ethereum Event Forwarder Docker Image: directory 'ethereum-event-forwarder' with source code exists."
                Utils.markStageSkippedForConditional('Pull latest Ethereum Event Forwarder Docker Image')
            } else {
                withDockerRegistry(dockerCredentials) {
                    sh label: 'Pull latest Ethereum Event Forwarder Docker Image', script: """#!/bin/bash -e
                        docker pull vegaprotocol/ethereum-event-forwarder:latest
                    """
                }
            }
        }
    }]
}

//
// Prepare Vegatools
//
Map<String,Closure> getPrepareVegatoolsStages() {
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
        /*stage('Build Vegatools Docker Image') {
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
        }*/
    }]
}

//
// Prepare Dockerised Vega
//
Map<String,Closure> getPrepareDockerisedVegaStages(
    DockerisedVega dockerisedVega,
    Map<String,String> dockerCredentials
) {
    return ['dv': {
        stage('Setup') {
            sh label: 'Create dockerised vega basedir',
                script: "mkdir -p '${dockerisedVega.basedir}'"
        }
        stage('Create config files') {
            if (dockerisedVega.genesisFile?.trim()) {
                // it contains either json or a path to a file
                String genesis = dockerisedVega.genesisFile.trim()
                String genesisFile = "/tmp/genesis-${dockerisedVega.prefix}.json"
                if (fileExists(genesis)) {
                    sh label: 'copy genesis file', script: """#!/bin/bash -e
                        cp "${genesis}" "${genesisFile}"
                    """
                } else {
                    writeFile(file: genesisFile, text: genesis)
                }
                dockerisedVega.genesisFile = genesisFile
                sh label: 'Custom genesis override file', script: """#!/bin/bash -e
                    echo "Content of ${genesisFile}"
                    echo "----------------"
                    cat "${genesisFile}"
                    echo "----------------"
                """
            }
            if (dockerisedVega.marketProposalsFile?.trim()) {
                // it contains either json or a path to a file
                String marketProposals = dockerisedVega.marketProposalsFile.trim()
                String marketProposalsFile = "/tmp/proposals-${dockerisedVega.prefix}.json"
                if (fileExists(marketProposals)) {
                    sh label: 'copy market proposals file', script: """#!/bin/bash -e
                        cp "${marketProposals}" "${marketProposalsFile}"
                    """
                } else {
                    writeFile(file: marketProposalsFile, text: marketProposals)
                }
                dockerisedVega.marketProposalsFile = marketProposalsFile
                sh label: 'Custom market proposals file', script: """#!/bin/bash -e
                    echo "Content of ${marketProposalsFile}"
                    echo "----------------"
                    cat "${marketProposalsFile}"
                    echo "----------------"
                """
            }
        }
        stage('Docker Pull') {
            retry(3) {
                withDockerRegistry(dockerCredentials) {
                    dockerisedVega.pull()
                }
            }
        }
    }]
}

/* void removeDockerImages(List<String> dockerImages) {
    for (String image : dockerImages) {
        sh label: "Remove docker image ${image}", script: """#!/bin/bash -e
            [ -z "\$(docker images -q "${image}")" ] || docker rmi "${image}"
        """
    }
}*/

void runMainStages(Closure inputMainStage, DockerisedVega dockerisedVega, Map vars) {
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
                pkill -f "${dockerLogsCommand} ${dockerisedVega.prefix}"
                """
            }
        }
    }

    // Start tailing logs in all containers
    for (String containerName : dockerisedVega.getDockerContainerNames()) {
        String longName = containerName
        String shortName = longName - "${dockerisedVega.prefix}-"
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
