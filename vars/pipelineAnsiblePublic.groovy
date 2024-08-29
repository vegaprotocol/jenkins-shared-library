void call(Map config=[:]) {
    if (!config.containsKey('playbookName') || config.playbookName.length < 2) {
        error("Pass non empty config.playbookName to the pipelinePublicAnsible call")
    }

    String playbookName = config.playbookName
    Map<String, String> extraVariables = config.extraVariables ?: [:]
    String ansibleTestnetAutomationBranch = config.ansibleTestnetAutomationBranch ?: 'main'
    String networksConfigPrivateBranch = config.networksConfigPrivateBranch ?: 'main'
    String nodeLabel = config.nodeLabel ?: ''

    pipeline {
        agent {
            label nodeLabel
        }
        options {
            ansiColor('xterm')
            skipDefaultCheckout()
            timestamps()
            timeout(time: 35, unit: 'MINUTES')
        }
        environment {
        }
        stages {
            stage('Pull repositories') {
                parallel {
                    stage('ansible-testnet-automation') {
                        gitClone(
                            directory: 'ansible-testnet-automation',
                            vegaUrl: 'ansible-testnet-automation',
                            branch: ansibleTestnetAutomationBranch,
                            extensions: [[$class: 'LocalBranch', localBranch: "**" ]]
                        )
                    }

                    stage('networks-config-private') {
                        gitClone(
                            directory: 'networks-config-private',
                            vegaUrl: 'networks-config-private',
                            branch: networksConfigPrivateBranch,
                            extensions: [[$class: 'LocalBranch', localBranch: "**" ]]
                        )
                    }
                }
            }
        }
    }
            
}