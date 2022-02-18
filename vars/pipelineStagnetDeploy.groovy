/* groovylint-disable DuplicateStringLiteral */
/* groovylint-disable DuplicateNumberLiteral */
/* groovylint-disable MethodSize */
/* groovylint-disable NestedBlockDepth */
import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException
import org.jenkinsci.plugins.pipeline.modeldefinition.Utils

void call() {

    properties([
        copyArtifactPermission('*'),
        disableConcurrentBuilds(),
        parameters([
            choice(
                name: 'NETWORK', choices: [/*'Stagnet 1',*/ 'Stagnet 2'],  // First on a list is the default one
                description: 'Select Stagnet Network'),
            text(
                name: 'REASON', defaultValue: pipelineDefaults.stag.reason,
                description: 'In a few words.'),
            booleanParam(
                name: 'DEPLOY_CONFIG', defaultValue: pipelineDefaults.stag.deployConfig,
                description: 'Deploy some Vega Network config, e.g. genesis file (run Ansible)'),
            booleanParam(
                name: 'RESTART', defaultValue: pipelineDefaults.stag.restart,
                description: 'Restart the Network'),
            booleanParam(
                name: 'CREATE_MARKETS', defaultValue: pipelineDefaults.stag.createMarkets,
                description: 'Create Markets'),
            booleanParam(
                name: 'BOUNCE_BOTS', defaultValue: pipelineDefaults.stag.bounceBots,
                description: 'Start & Top up liqbot and traderbot with fake/ERC20 tokens'),
            string(
                name: 'DEVOPS_INFRA_BRANCH', defaultValue: pipelineDefaults.stag.devopsInfraBranch,
                description: '''Git branch, tag or hash of the vegaprotocol/devops-infra repository
                <h4>Do NOT modify</h4>, unless you are 100% sure what you are doing.'''),
            string(
                name: 'VEGA_CORE_VERSION', defaultValue: pipelineDefaults.stag.vegaCoreVersion,
                description: '''Deploy a version to the Fairground. NOTE: must be in https://github.com/vegaprotocol/vega/releases
                Leave empty to not deploy a new version of vega core.
                <h4>Do NOT set</h4>, unless you are 100% sure what you are doing.'''),
        ])
    ])

    echo "params=${params}"

    node {
        String networkID = 'UNKNOWN'
        if (params.NETWORK == 'Stagnet 1') {
            networkID = 'stagnet'
        } else if (params.NETWORK == 'Stagnet 2') {
            networkID = 'stagnet2'
        } else {
            error("Unsupported network ${params.NETWORK}.")
        }

        // NOTE: environment variables PSSH_USER and PSSH_KEYFILE are used by veganet.sh script
        /* groovylint-disable-next-line NoDef, VariableTypeRequired */
        def sshStagnetCredentials = sshUserPrivateKey(  credentialsId: 'ssh-vega-network',
                                                      keyFileVariable: 'PSSH_KEYFILE',
                                                     usernameVariable: 'PSSH_USER')
        Map dockerCredentials = [credentialsId: 'github-vega-ci-bot-artifacts',
                                           url: 'https://ghcr.io']
        skipDefaultCheckout()
        cleanWs()

        timestamps {
            try {
                timeout(time: 60, unit: 'MINUTES') {
                    stage('CI config') {
                        // Printout all configuration variables
                        sh 'printenv'
                        echo "params=${params.inspect()}"
                        echo "networkID=${networkID}"
                    }
                    //
                    // GIT clone
                    //
                    stage('Git Clone') {
                        parallel([
                            'devops-infra': {
                                dir('devops-infra') {
                                    gitClone('devops-infra', params.DEVOPS_INFRA_BRANCH)
                                }
                            },
                            'vega core': {
                                if (params.VEGA_CORE_VERSION) {
                                    dir('vega') {
                                        gitClone('vega', params.VEGA_CORE_VERSION)
                                    }
                                } else {
                                    echo 'Skip: VEGA_CORE_VERSION not specified'
                                    Utils.markStageSkippedForConditional('vega core')
                                }
                            }
                        ])
                    }
                    //
                    // BUILD
                    //
                    String buildVegaCoreStageName = 'Build Vega Core binary'
                    stage('Prepare') {
                        parallel([
                            "${buildVegaCoreStageName}": {
                                if (params.VEGA_CORE_VERSION) {
                                    dir('vega') {
                                        String hash = sh(
                                            script: 'git rev-parse HEAD|cut -b1-8',
                                            returnStdout: true,
                                        ).trim()
                                        String ldflags = "-X main.CLIVersion=dev-${hash} -X main.CLIVersionHash=${hash}"
                                        sh label: 'Compile vega core', script: """
                                            go build -v -o ./cmd/vega/vega-linux-amd64 -ldflags "${ldflags}" ./cmd/vega
                                        """
                                        sh label: 'Sanity check', script: '''
                                            file ./cmd/vega/vega-linux-amd64
                                            ./cmd/vega/vega-linux-amd64 version
                                        '''
                                    }
                                } else {
                                    echo 'Skip: VEGA_CORE_VERSION not specified'
                                    Utils.markStageSkippedForConditional(buildVegaCoreStageName)
                                }
                            },
                            'veganet docker pull': {
                                dir('devops-infra') {
                                    withDockerRegistry(dockerCredentials) {
                                        sh script: './veganet.sh devnet pull'
                                    }
                                }
                            }
                        ])
                    }
                    //
                    // Network Status
                    //
                    stage("${env.NETWORK}: status") {
                        dir('devops-infra') {
                            withDockerRegistry(dockerCredentials) {
                                withCredentials([sshDevnetCredentials]) {
                                    sh script: './veganet.sh devnet status'
                                }
                            }
                        }
                    }
                    //
                    // DEPLOY binary
                    //
                    String deployStageName = 'Deploy Vega Core binary'
                    stage(deployStageName) {
                        if (params.VEGA_CORE_VERSION) {
                            withEnv([
                                "VEGA_CORE_BINARY=${env.WORKSPACE}/vega/cmd/vega/vega-linux-amd64",
                            ]) {
                                dir('devops-infra') {
                                    withDockerRegistry(dockerCredentials) {
                                        withCredentials([sshDevnetCredentials]) {
                                            sh script: './veganet.sh devnet pushvega'
                                        }
                                    }
                                }
                            }
                        } else {
                            echo 'Skip: VEGA_CORE_VERSION not specified'
                            Utils.markStageSkippedForConditional(deployStageName)
                        }
                    }
                    //
                    // DEPLOY ansible config
                    //
                    optionalStage(
                        name: 'Deploy Vega Network Config',
                        skip: !params.DEPLOY_CONFIG,
                    ) {
                        dir('devops-infra/ansible') {
                            withCredentials([sshStagnetCredentials]) {
                                // Note: environment variables PSSH_KEYFILE and PSSH_USER
                                //        are set by withCredentials wrapper
                                sh label: 'ansible deploy run', script: """#!/bin/bash -e
                                    export ANSIBLE_FORCE_COLOR=true
                                    ansible-playbook \
                                        --diff \
                                        -u "\${PSSH_USER}" \
                                        --private-key "\${PSSH_KEYFILE}" \
                                        -i hosts \
                                        --limit ${networkID} \
                                        --tags vega-network-config \
                                        site.yaml
                                """
                            }
                        }
                    }
                    //
                    // RESTART network
                    //
                    String restartStageName = 'Restart Network'
                    stage(restartStageName) {
                        if (params.RESTART) {
                            dir('devops-infra') {
                                withDockerRegistry(dockerCredentials) {
                                    withCredentials([sshStagnetCredentials]) {
                                        sh script: "./veganet.sh ${networkID} bounce"
                                    }
                                }
                            }
                        } else {
                            echo "Skip: selected '{params.RESTART}' option"
                            Utils.markStageSkippedForConditional(restartStageName)
                        }
                    }
                    //
                    // CREATE markets
                    //
                    String createMarketsStageName = 'Create Markets'
                    stage(createMarketsStageName) {
                        if (params.CREATE_MARKETS) {
                            dir('devops-infra') {
                                withDockerRegistry(dockerCredentials) {
                                    withCredentials([sshDevnetCredentials]) {
                                        sh script: './veganet.sh devnet create_markets'
                                    }
                                }
                            }
                        } else {
                            echo 'Skip: CREATE_MARKETS is false'
                            Utils.markStageSkippedForConditional(createMarketsStageName)
                        }
                    }
                    //
                    // BOUNCE bots
                    //
                    String bounceBotsStageName = 'Bounce Bots'
                    stage(bounceBotsStageName) {
                        if (params.BOUNCE_BOTS) {
                            dir('devops-infra') {
                                withDockerRegistry(dockerCredentials) {
                                    withCredentials([sshDevnetCredentials]) {
                                        sh script: './veganet.sh devnet bounce_bots'
                                    }
                                }
                            }
                        } else {
                            echo 'Skip: BOUNCE_BOTS is false'
                            Utils.markStageSkippedForConditional(bounceBotsStageName)
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
                    slack.slackSendDeployStatus network: 'Stagnet 2',
                        version: params.VEGA_CORE_VERSION,
                        restart: params.RESTART
                }
            }
        }
    }
}

void gitClone(String repo, String branch) {
    retry(3) {
        checkout([
            $class: 'GitSCM',
            branches: [[name: branch]],
            userRemoteConfigs: [[
                url: "git@github.com:vegaprotocol/${repo}.git",
                credentialsId: 'vega-ci-bot'
            ]]])
    }
}
