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
            booleanParam(
                name: 'RESTART', defaultValue: pipelineDefaults.fair.createMarkets,
                description: 'Restart the Network'),
            booleanParam(
                name: 'CREATE_MARKETS', defaultValue: pipelineDefaults.fair.createMarkets,
                description: 'Create Markets'),
            booleanParam(
                name: 'BOUNCE_BOTS', defaultValue: pipelineDefaults.fair.bounceBots,
                description: 'Start & Top up liqbot and traderbot with fake/ERC20 tokens'),
            string(
                name: 'DEVOPS_INFRA_BRANCH', defaultValue: pipelineDefaults.fair.devopsInfraBranch,
                description: 'Git branch, tag or hash of the vegaprotocol/devops-infra repository'),
            string(
                name: 'VEGA_CORE_VERSION', defaultValue: pipelineDefaults.fair.vegaCoreVersion,
                description: '''Git branch, tag or hash of the vegaprotocol/vega repository.
                Leave empty to not deploy a new version of vega core.'''),
        ])
    ])

    echo "params=${params}"

    node {
        // NOTE: environment variables PSSH_USER and PSSH_KEYFILE are used by veganet.sh script
        /* groovylint-disable-next-line NoDef, VariableTypeRequired */
        def sshFairgroundCredentials = sshUserPrivateKey(  credentialsId: 'ssh-vega-network',
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
                    }
                    //
                    // GIT clone
                    //
                    stage('Git Clone') {
                        dir('devops-infra') {
                            gitClone('devops-infra', params.DEVOPS_INFRA_BRANCH)
                        }
                    }
                    //
                    // PREPARE
                    //
                    stage('Prepare') {
                        dir('devops-infra') {
                            withDockerRegistry(dockerCredentials) {
                                withCredentials([sshFairgroundCredentials]) {
                                    sh script: './veganet.sh testnet pull'
                                }
                            }
                        }
                    }
                    //
                    // STATUS
                    //
                    stage('Fairground: status') {
                        dir('devops-infra') {
                            withDockerRegistry(dockerCredentials) {
                                withCredentials([sshFairgroundCredentials]) {
                                    sh script: './veganet.sh testnet status'
                                }
                            }
                        }
                    }
                    //
                    // DEPLOY version
                    //
                    String deployStageName = 'Deploy Vega Core version'
                    stage(deployStageName) {
                        if (params.VEGA_CORE_VERSION) {
                            withEnv([
                                "TAG=${params.VEGA_CORE_VERSION}",
                            ]) {
                                dir('devops-infra') {
                                    withDockerRegistry(dockerCredentials) {
                                        withCredentials([sshFairgroundCredentials]) {
                                            sh script: './veganet.sh testnet getvega'
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
                    // RESTART network
                    //
                    String restartStageName = 'Restart Network'
                    stage(restartStageName) {
                        if (params.RESTART) {
                            dir('devops-infra') {
                                withDockerRegistry(dockerCredentials) {
                                    withCredentials([sshFairgroundCredentials]) {
                                        sh script: './veganet.sh testnet stopbots stop'
                                        sh script: './veganet.sh testnet chainstorecopy vegalogcopy'
                                        sh script: './veganet.sh testnet nukedata'
                                        sh script: './veganet.sh testnet vegareinit'
                                        sh script: './veganet.sh testnet start_datanode start'
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
                                    withCredentials([sshFairgroundCredentials]) {
                                        sh script: './veganet.sh testnet create_markets'
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
                                    withCredentials([sshFairgroundCredentials]) {
                                        sh script: './veganet.sh testnet bounce_bots'
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
                    slack.slackSendCIStatus channel: '#env-deploy',
                        name: 'Restart the Fairground network',
                        branch: 'Top-Up'
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
