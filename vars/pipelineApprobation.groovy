/* groovylint-disable DuplicateStringLiteral */
/* groovylint-disable DuplicateNumberLiteral */
/* groovylint-disable MethodSize */
/* groovylint-disable NestedBlockDepth */
import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException
import org.jenkinsci.plugins.workflow.support.steps.build.RunWrapper
import org.jenkinsci.plugins.pipeline.modeldefinition.Utils

void call() {

    String scriptSlackMsg = ''

    echo "buildCauses=${currentBuild.buildCauses}"
    if (currentBuild.upstreamBuilds) {
        RunWrapper upBuild = currentBuild.upstreamBuilds[0]
        currentBuild.displayName = "#${currentBuild.id} - ${upBuild.fullProjectName} #${upBuild.id}"
    }

    properties([
        copyArtifactPermission('*'),
        parameters([
            string(
                name: 'ORIGIN_REPO', defaultValue: 'vegaprotocol/vega',
                description: 'Git branch, tag or hash of the vegaprotocol/vega repository'),
            string(
                name: 'VEGA_CORE_BRANCH', defaultValue: pipelineDefaults.appr.vegaCoreBranch,
                description: 'Git branch, tag or hash of the origin repo repository'),
            string(
                name: 'SPECS_BRANCH', defaultValue: pipelineDefaults.appr.specsBranch,
                description: 'Git branch, tag or hash of the vegaprotocol/specs repository'),
            string(
                name: 'MULTISIG_CONTROL_BRANCH', defaultValue: pipelineDefaults.appr.multisigControlBranch,
                description: 'Git branch, tag or hash of the vegaprotocol/MultisigControl repository'),
            string(
                name: 'SYSTEM_TESTS_BRANCH', defaultValue: pipelineDefaults.appr.systemTestsBranch,
                description: 'Git branch, tag or hash of the vegaprotocol/system-tests repository'),

            string(
                name: 'SPECS_ARG', defaultValue: pipelineDefaults.appr.specsArg,
                description: '--specs argument value'),
            string(
                name: 'TESTS_ARG', defaultValue: pipelineDefaults.appr.testsArg,
                description: '--tests argument value'),
            string(
                name: 'IGNORE_ARG', defaultValue: pipelineDefaults.appr.ignoreArg,
                description: '--ignore argument value'),
            string(
                name: 'OTHER_ARG', defaultValue: pipelineDefaults.appr.otherArg,
                description: 'Other arguments'),

            string(
                name: 'APPROBATION_VERSION', defaultValue: pipelineDefaults.appr.approbationVersion,
                description: 'Released version of Approbation. latest can be used'),
        ])
    ])

    echo "params=${params}"

    node {
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
                            'vega core': {
                                dir('vega') {
                                    gitClone(params.ORIGIN_REPO, params.VEGA_CORE_BRANCH)
                                }
                            },
                            'specs': {
                                dir('specs') {
                                    gitClone('vegaprotocol/specs', params.SPECS_BRANCH)
                                }
                            },
                            'MultisigControl': {
                                dir('MultisigControl') {
                                    gitClone('vegaprotocol/MultisigControl', params.MULTISIG_CONTROL_BRANCH)
                                }
                            },
                            'system-tests': {
                                dir('system-tests') {
                                    gitClone('vegaprotocol/system-tests', params.SYSTEM_TESTS_BRANCH)
                                }
                            }
                        ])
                    }
                    //
                    // RUN
                    //
                    stage('Run Approbation') {
                        sh label: 'approbation', script: """#!/bin/bash -e
                            npx @vegaprotocol/approbation@${params.APPROBATION_VERSION} check-references \
                                --specs="${params.SPECS_ARG}" \
                                --tests="${params.TESTS_ARG}" \
                                --ignore="${params.IGNORE_ARG}" \
                                ${params.OTHER_ARG}
                        """
                    }
                    //
                    // Results
                    //
                    stage('Store results') {
                        archiveArtifacts artifacts: 'results/*',
                                allowEmptyArchive: true
                        scriptSlackMsg = sh(
                            script: "cat results/jenkins.txt",
                            returnStdout: true,
                        ).trim()
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
                    sendSlackMessage(scriptSlackMsg)
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
                url: "git@github.com:${repo}.git",
                credentialsId: 'vega-ci-bot'
            ]]])
    }
}

void sendSlackMessage(String scriptMsg) {
    String slackChannel = '#coverage-notify'
    String jobURL = env.RUN_DISPLAY_URL
    String jobName = currentBuild.displayName

    String currentResult = currentBuild.result ?: currentBuild.currentResult
    String duration = currentBuild.durationString - ' and counting'
    String msg = ''
    String color = ''

    if (currentResult == 'SUCCESS') {
        msg = ":large_green_circle: Approbation <${jobURL}|${jobName}>"
        color = 'good'
    } else if (currentResult == 'ABORTED') {
        msg = ":black_circle: Approbation aborted <${jobURL}|${jobName}>"
        color = '#000000'
    } else {
        msg = ":red_circle: Approbation <${jobURL}|${jobName}>"
        color = 'danger'
    }

    msg += " (${duration})"

    if (scriptMsg != '') {
        msg += "\n${scriptMsg}"
    }

    slackSend(
        channel: slackChannel,
        color: color,
        message: msg,
    )
}
