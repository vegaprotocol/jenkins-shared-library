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
        pipelineTriggers([cron('H */4 * * *')]),
        parameters([
            string(
                name: 'DEVOPS_INFRA_BRANCH', defaultValue: pipelineDefaults.fair.devopsInfraBranch,
                description: 'Git branch, tag or hash of the vegaprotocol/devops-infra repository'),
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
                timeout(time: 15, unit: 'MINUTES') {
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
                    // Status
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
                    // BOUNCE bots
                    //
                    String bounceBotsStageName = 'Bounce and top-up Bots'
                    stage(bounceBotsStageName) {
                        dir('devops-infra') {
                            withDockerRegistry(dockerCredentials) {
                                withCredentials([sshFairgroundCredentials]) {
                                    sh script: 'SKIP_REMOVE_BOT_WALLETS=true ./veganet.sh testnet bounce_bots'
                                }
                            }
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
                        name: 'Fairground Top-Up Bots',
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
