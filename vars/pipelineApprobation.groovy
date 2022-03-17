/* groovylint-disable DuplicateStringLiteral */
/* groovylint-disable DuplicateNumberLiteral */
/* groovylint-disable MethodSize */
/* groovylint-disable NestedBlockDepth */
import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException
import org.jenkinsci.plugins.pipeline.modeldefinition.Utils

void call() {

    properties([
        copyArtifactPermission('*'),
        parameters([
            string(
                name: 'VEGA_CORE_VERSION', defaultValue: pipelineDefaults.appr.vegaCoreVersion,
                description: 'Git branch, tag or hash of the vegaprotocol/vega repository'),
            string(
                name: 'SPECS_INTERNAL_VERSION', defaultValue: pipelineDefaults.appr.specsInternalVersion,
                description: 'Git branch, tag or hash of the vegaprotocol/specs-internal repository'),
            string(
                name: 'MULTISIG_CONTROL_VERSION', defaultValue: pipelineDefaults.appr.multisigControlVersion,
                description: 'Git branch, tag or hash of the vegaprotocol/MultisigControl repository'),
            string(
                name: 'SYSTEM_TESTS_VERSION', defaultValue: pipelineDefaults.appr.systemTestsVersion,
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
                                    gitClone('vega', params.VEGA_CORE_VERSION)
                                }
                            },
                            'specs-internal': {
                                dir('specs-internal') {
                                    gitClone('specs-internal', params.SPECS_INTERNAL_VERSION)
                                }
                            },
                            'MultisigControl': {
                                dir('MultisigControl') {
                                    gitClone('MultisigControl', params.MULTISIG_CONTROL_VERSION)
                                }
                            },
                            'system-tests': {
                                dir('system-tests') {
                                    gitClone('system-tests', params.SYSTEM_TESTS_VERSION)
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
                    echo 'Post something to Slack?'
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
