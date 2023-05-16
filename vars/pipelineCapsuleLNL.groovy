/* groovylint-disable LineLength */
void call(Map paramsOverrides=[:]) {
    capsuleSystemTests([
        agentLabel: params.NODE_LABEL ?: '',
        vegacapsuleConfig: 'mainnet_config.hcl',
        systemTestsBranch: 'lnl-pipeline',
        extraEnvVars: [
            'MAINNET_TEST_CASE': 'true',
        ],
        fastFail: false,
        slackTitle: 'LNL Mainnet System Tests',
        hooks: [
            postNetworkGenerate: [
                'Load mainnet checkpoint': {
                    def sshCredentials = sshUserPrivateKey(
                        credentialsId: 'ssh-vega-network',
                        keyFileVariable: 'PSSH_KEYFILE',
                        usernameVariable: 'PSSH_USER'
                    )

                    withCredentials([sshCredentials]) {
                        String networkDir = ""
                        dir (pipelineDefaults.capsuleSystemTests.systemTestsNetworkDir) {
                            networkDir = vegautils.escapePath(pwd())
                        }

                        List availableCheckpointServers = [
                            'api0.vega.community',
                            'api1.vega.community',
                            'api2.vega.community',
                        ]
                        String tendermintRestAPIUrl = 'https://be.vega.community'

                        Random rnd = new Random()
                        String selectedCheckpointSourceServer = availableCheckpointServers[rnd.nextInt(availableCheckpointServers.size)]
                        print('Random server for checkpoint source: ' + selectedCheckpointSourceServer)
                        sh label: 'Prepare mainnet genesis', script: '''mkdir -p ./lnl-workdir;
                            devopsscripts lnl prepare-network \
                                --checkpoint-server-checkpoint-dir "/home/vega/vega_home/state/node/checkpoints" \
                                --checkpoint-server-host "''' + selectedCheckpointSourceServer + '''" \
                                --tendermint-rest-api-url "''' + tendermintRestAPIUrl + '''" \
                                --checkpoint-server-key-file "''' + PSSH_KEYFILE + '''" \
                                --checkpoint-server-user "''' + PSSH_USER + '''" \
                                --genesis-uri "file://system-tests/vegacapsule/net_configs/mainnet/genesis.json" \
                                --vegacapsule-network-home "''' + networkDir + '''/testnet" \
                                --out-dir "./lnl-workdir" \
                                --vegacapsule-path "vegacapsule" \
                                --vega-path "vega" \
                                --no-secrets
                        '''

                        sh label: 'Copy loaded checkpoint into system-tests directory', script: '''
                            cp ./lnl-workdir/checkpoint.json system-tests/tests/LNL/checkpoint-resume.json
                        '''
                    }
                }
            ],
            runTests: [
                'Copy checkpoint after load': {
                    String networkDir = ""
                    dir (pipelineDefaults.capsuleSystemTests.systemTestsNetworkDir) {
                        networkDir = vegautils.escapePath(pwd())
                    }

                    rawPath = sh(returnStdout:true,
                        script: '''
                        devopsscripts vegacapsule wait-for-checkpoint \
                            --checkpoints 1 \
                            --timeout 10m \
                            --print-last-checkpoint-path-only \
                            --search-from-beginning \
                            --vegacapsule-path vegacapsule \
                            --network-home-path "''' + networkDir + '''/testnet" \
                            --no-secrets
                        '''
                    ).trim()
                    
                    checkpointPath = vegautils.escapePath(rawPath)
                    sh label: 'Copy found checkpoint', 
                    script: '''vegatools checkpoint \
                        --file "''' + checkpointPath + '''" \
                        --out system-tests/tests/LNL/after_checkpoint_load.json \
                        1> /dev/null
                    '''
                }
            ],
            postRunTests: [
                'Extended LNL pipeline': {
                  dir('system-tests/scripts') {
                    sh 'TEST_FUNCTION=test_extended_lnl make test'
                    }
                }
            ],
            postPipeline: [
                'Archive checkpoints and genesis': {
                    [
                        'system-tests/tests/LNL/checkpoint-resume.json',
                        'system-tests/tests/LNL/after_checkpoint_load.json',
                        'system-tests/vegacapsule/net_configs/mainnet/genesis.json'
                    ].each {
                        if (fileExists(it)) {
                            archiveArtifacts(
                                artifacts: it,
                                allowEmptyArchive: true
                            )
                        } else {
                            print('[WARN] Artifact ' + it + ' not found. Archive skipped')
                        }
                    }
                }
            ]
        ],
    ], paramsOverrides)
}