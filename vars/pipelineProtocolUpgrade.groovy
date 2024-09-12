int networkHeight(String envName) {
    if (envName == 'fairground') {
        envName = 'testnet'
    }

    int maxHeight = 0
    for (int i=0; i<10; i++) {
        String nodeUrl = "https://n0" + i + "." + envName + ".vega.rocks/statistics"
        try {
            URLConnection conn = new URL(nodeUrl).openConnection()
            int rc = conn.getResponseCode()
            if (rc == 200) {
                Map stats = readJSON text: conn.getInputStream().getText()
                String heightStr = stats?.statistics?.blockHeight ?: 0
                int height = heightStr as int
                if (height > maxHeight) {
                    maxHeight = height
                }
            }
        } catch (e) {
            println(e)
        }
    }

    return maxHeight
}
// def jsonObj = readJSON text: jsonString


void call() {
    String vegaVersion = params.VEGA_VERSION ?: ''
    String environmentName = env.NET_NAME ?: ''
    String upgradeBlock = params.UPGRADE_BLOCK ?: ''

    String ansibleTestnetAutomationBranch = params.ANSIBLE_TEST_AUTOMATION_BRANCH ?: 'main'
    String networksConfigPrivateBranch = params.NETWORKS_CONFIG_PRIVATE_BRANCH ?: 'main'

    int finalUpdateBlock = 0

    pipeline {
        agent {
            label params.NODE_LABEL
        }
        // agent any
        options {
            ansiColor('xterm')
            skipDefaultCheckout()
            timestamps()
            timeout(time: 35, unit: 'MINUTES')
        }
        // environment {
        // }
        stages {
            stage('CI config') {
                steps {
                    script {
                        List<String> description = []
                        
                        vegautils.commonCleanup()

                        if (vegaVersion.length() < 1) {
                            error("Version cannot be empty");
                        }

                        int upgradeHeightI = 0 

                        try {
                            upgradeHeightI = upgradeBlock as int
                        } catch(e) {}

                        if (upgradeHeightI < 100) {
                            upgradeHeightI = networkHeight(environmentName)
                            if (upgradeHeightI < 100) {
                                error("Cannot detect network height automatically. Provide it manually or find the issue.")
                            }
                        }
                        finalUpdateBlock = upgradeHeightI + 300

                        currentBuild.description = "Block: " + finalUpdateBlock + ", version: " + vegaVersion
                    }
                }
            }

            stage('Perform protocol upgrade') {
                steps {
                    ansiblePublicPlaybook([
                        'playbookName': 'vega-network-protocol-upgrade',
                        'hostsLimit': environmentName,
                        'dryRun': false,
                        'ansibleTestnetAutomationBranch': ansibleTestnetAutomationBranch,
                        'networksConfigPrivateBranch': networksConfigPrivateBranch,
                        'withGitClone': true,
                        'extraVariables': [
                            'vega_protocol_upgrade_version': vegaVersion,
                            'vega_protocol_upgrade_height': finalUpdateBlock
                        ]
                    ])
                }
            }
        } // stages
    } // pipeline
} // void call