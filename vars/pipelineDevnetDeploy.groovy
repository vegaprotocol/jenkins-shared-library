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
            string(
                name: 'VEGA_CORE_VERSION', defaultValue: pipelineDefaults.dev.vegaCoreVersion,
                description: '''Git branch, tag or hash of the vegaprotocol/vega repository.
                Leave empty to not deploy a new version of vega core.'''),
            booleanParam(
                name: 'DEPLOY_CONFIG', defaultValue: pipelineDefaults.dev.deployConfig,
                description: 'Deploy some Vega Network config, e.g. genesis file'),
            choice(
                name: 'RESTART', choices: [
                    pipelineDefaults.restartOptions.restartOnly,
                    pipelineDefaults.restartOptions.restartFromCheckpoint,
                    pipelineDefaults.restartOptions.dontRestart,
                ],
                description: 'Restart the Network'),
            booleanParam(
                name: 'CREATE_MARKETS', defaultValue: pipelineDefaults.dev.createMarkets,
                description: 'Create Markets'),
            booleanParam(
                name: 'CREATE_INCENTIVE_MARKETS', defaultValue: pipelineDefaults.dev.createIncentiveMarkets,
                description: 'Create Markets for Incentive'),
            booleanParam(
                name: 'BOUNCE_BOTS', defaultValue: pipelineDefaults.dev.bounceBots,
                description: 'Start & Top up liqbot and traderbot with fake/ERC20 tokens'),
            string(
                name: 'DEVOPS_INFRA_BRANCH', defaultValue: pipelineDefaults.dev.devopsInfraBranch,
                description: 'Git branch, tag or hash of the vegaprotocol/devops-infra repository'),
            string(
                name: 'ANSIBLE_BRANCH', defaultValue: 'master',
                description: 'Git branch, tag or hash of the vegaprotocol/ansible repository'),
        ])
    ])

    echo "params=${params}"

    node {
        // NOTE: environment variables PSSH_USER and PSSH_KEYFILE are used by veganet.sh script
        /* groovylint-disable-next-line NoDef, VariableTypeRequired */
        def sshDevnetCredentials = sshUserPrivateKey(  credentialsId: 'ssh-vega-network',
                                                     keyFileVariable: 'PSSH_KEYFILE',
                                                    usernameVariable: 'PSSH_USER')
        Map dockerCredentials = [credentialsId: 'github-vega-ci-bot-artifacts',
                                           url: 'https://ghcr.io']
        skipDefaultCheckout()
        cleanWs()

        timestamps {
            try {
                timeout(time: 20, unit: 'MINUTES') {
                    stage('CI config') {
                        // Printout all configuration variables
                        sh 'printenv'
                        echo "params=${params.inspect()}"
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
                            },
                            'ansible': {
                                dir('ansible') {
                                    gitClone('ansible', params.ANSIBLE_BRANCH)
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
                                        sh label: 'Compile vega core', script: """
                                            go build -v -o ./cmd/vega/vega-linux-amd64 ./cmd/vega
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
                    stage('Devnet: status') {
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
                    String deployConfigStageName = 'Deploy Vega Network Config'
                    stage(deployConfigStageName) {
                        if (params.DEPLOY_CONFIG) {
                            dir('ansible') {
                                withCredentials([sshDevnetCredentials]) {
                                    // Note: environment variables PSSH_KEYFILE and PSSH_USER
                                    //        are set by withCredentials wrapper
                                    script {
                                        ['tendermint', 'vegaserver'].each { playbook ->
                                            sh label: 'ansible deploy run', script: """#!/bin/bash -e
                                                ansible-playbook \
                                                    --diff \
                                                    -u "\${PSSH_USER}" \
                                                    --private-key "\${PSSH_KEYFILE}" \
                                                    --inventory inventories \
                                                    --limit ${env.NET_NAME} \
                                                    --tags vega-network-config \
                                                    playbooks/playbook-${playbook}.yaml
                                            """
                                        }
                                    }
                                }
                            }
                        } else {
                            echo 'Skip: DEPLOY_CONFIG is false'
                            Utils.markStageSkippedForConditional(deployConfigStageName)
                        }
                    }
                    //
                    // RESTART network
                    //
                    String restartStageName = 'Restart Network'
                    stage(restartStageName) {
                        if (params.RESTART != pipelineDefaults.restartOptions.dontRestart) {
                            withEnv([
                                "RESTORE_FROM_CHECKPOINT=${params.RESTART == pipelineDefaults.restartOptions.restartFromCheckpoint ? 'yes' : 'no'}",
                            ]) {
                                dir('devops-infra') {
                                    withDockerRegistry(dockerCredentials) {
                                        withCredentials([sshDevnetCredentials]) {
                                            sh script: './veganet.sh devnet bounce'
                                        }
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
                    // CREATE Incentive markets
                    //
                    String createIncentiveMarketsStageName = 'Create Incentive Markets'
                    stage(createIncentiveMarketsStageName) {
                        if (params.CREATE_INCENTIVE_MARKETS) {
                            dir('devops-infra') {
                                withDockerRegistry(dockerCredentials) {
                                    withCredentials([sshDevnetCredentials]) {
                                        sh script: './veganet.sh devnet incentive_create_markets'
                                    }
                                }
                            }
                        } else {
                            echo 'Skip: CREATE_INCENTIVE_MARKETS is false'
                            Utils.markStageSkippedForConditional(createIncentiveMarketsStageName)
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
                                        withGoogleSA('gcp-k8s') {
                                            sh script: './veganet.sh devnet bounce_bots'
                                        }
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
                    slack.slackSendDeployStatus network: 'Devnet',
                        version: params.VEGA_CORE_VERSION,
                        restart: params.RESTART != pipelineDefaults.restartOptions.dontRestart
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
