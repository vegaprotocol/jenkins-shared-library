/* groovylint-disable DuplicateStringLiteral, LineLength */
void call(Map paramsOverrides=[:]) {
    capsuleSystemTests([
        agentLabel: params.NODE_LABEL ?: '',
        systemTestsBranch: 'lnl-pipeline',
        extraEnvVars: [
            'MAINNET_TEST_CASE': 'true',
        ],
        fastFail: false,
        slackTitle: 'LNL Mainnet System Tests',
        hooks: [
            postNetworkGenerate: [
                'Download core snapshot from mainnet API': {
                    List mainnetApiServers = [
                    'api0.vega.community',
                    'api1.vega.community',
                    'api2.vega.community',
                    ]

                    Random rnd = new Random()
                    String selectedMainnetApiServer = mainnetApiServers[rnd.nextInt(mainnetApiServers.size)]

                    def sshCredentials = sshUserPrivateKey(
                        credentialsId: 'ssh-vega-network',
                        keyFileVariable: 'PSSH_KEYFILE',
                        usernameVariable: 'PSSH_USER'
                    )
                    String networkDir
                    dir (pipelineDefaults.capsuleSystemTests.systemTestsNetworkDir) {
                        networkDir = vegautils.escapePath(pwd())
                    }

                    withCredentials([sshCredentials]) {
                        print('Random server for snapshot source: ' + selectedMainnetApiServer)
                        sh label: 'Prepare mainnet genesis', script: '''
                            devopstools snapshot-compatibility load-snapshot \
                                --snapshot-remote-location "/home/vega/vega_home/state/node/snapshots" \
                                --snapshot-server "''' + selectedMainnetApiServer + '''" \
                                --snapshot-server-key-file "''' + PSSH_KEYFILE + '''" \
                                --snapshot-server-user "''' + PSSH_USER + '''" \
                                --vegacapsule-binary "vegacapsule" \
                                --vega-binary "vega" \
                                --vegacapsule-home "''' + networkDir + '''/testnet"
                        '''
                    }

                    sh label: 'Convert downloaded snapshot to JSON', script: '''
                        devopstools snapshot-compatibility collect-snapshot \
                            --snapshot-json-output snapshot-before.json \
                            --vegacapsule-binary "vegacapsule" \
                            --vega-binary "vega" \
                            --vegacapsule-home "''' + networkDir + '''/testnet"
                    '''
                }
            ],
            postNetworkStart: [
                'Move the network forward and produce a new snapshot': {
                    String networkDir
                    dir (pipelineDefaults.capsuleSystemTests.systemTestsNetworkDir) {
                        networkDir = vegautils.escapePath(pwd())
                    }
                    sh label: 'Produce snapshot', script: '''
                        devopstools snapshot-compatibility produce-new-snapshot \
                            --vegacapsule-binary "vegacapsule" \
                            --vegacapsule-home "''' + networkDir + '''/testnet"
                    '''

                    sh label: 'Convert the new snapshot to JSON', script: '''
                        devopstools snapshot-compatibility collect-snapshot \
                            --snapshot-json-output snapshot-after.json \
                            --vegacapsule-binary "vegacapsule" \
                            --vega-binary "vega" \
                            --vegacapsule-home "''' + networkDir + '''/testnet"
                    '''
                }
            ]
        ],
    ], [
        CAPSULE_CONFIG: 'capsule_config_mainnet_snapshot.hcl',
        DEVOPSTOOLS_BRANCH: 'add-snapshot-compatibility-helpers',
        SKIP_RUN_TESTS: true,
        SKIP_MULTISIGN_SETUP: true,
    ])
}