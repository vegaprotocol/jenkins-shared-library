/* groovylint-disable LineLength */
void call(Map paramsOverrides=[:]) {
    List mainnetApiServers = [
      'api0.vega.community',
      'api1.vega.community',
      'api2.vega.community',
    ]

    node(params.NODE_LABEL ?: '') {
        Boolean isMainnetVersionScenario = paramsOverrides.get("mainnetVersionScenario", false)
        if (isMainnetVersionScenario) {
        Map<String, ?> nodeStatistics = vegautils.networkStatistics(nodesList: mainnetApiServers)
        // When we have the mainnet scenario we target the vega version which is
        // runnin in the current mainnet. We overrides version collected from the
        // /statistics endpoint of the mainnet API server.

        paramsOverrides.put('VEGA_BRANCH', nodeStatistics['statistics']['appVersion'])
        print('This is pipeline targeting the mainnet version. Running with vega_branch = ' + nodeStatistics['statistics']['appVersion'])
        }
    }


    capsuleSystemTests([
        agentLabel: params.NODE_LABEL ?: '',
        vegacapsuleConfig: 'mainnet_config.hcl',
        extraEnvVars: [
            'MAINNET_TEST_CASE': 'true',
        ],
        fastFail: false,
        slackTitle: 'LNL Mainnet System Tests',
        hooks: [
            postNetworkGenerate: [
                'Load mainnet checkpoint': {
                    Random rnd = new Random()
                    String selectedMainnetApiServer = mainnetApiServers[rnd.nextInt(mainnetApiServers.size)]

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

                        String tendermintRestAPIUrl = 'https://be.vega.community'

                        print('Random server for checkpoint source: ' + selectedMainnetApiServer)
                        sh label: 'Prepare mainnet genesis', script: '''mkdir -p ./lnl-workdir;
                            devopsscripts lnl prepare-network \
                                --checkpoint-server-checkpoint-dir "/home/vega/vega_home/state/node/checkpoints" \
                                --checkpoint-server-host "''' + selectedMainnetApiServer + '''" \
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
            postNetworkStart: [
                'Fix for duplicates - vegaprotocol/vega 8662': {
                    // We have to run this pipeline because when the network is started, data-node runs the migrations
                    // before any data is loaded into the network. It means We run the `DELETE FROM ...` queries when the
                    // tables are empty. Then after protocol upgrade we do not run the same migration again because 
                    // the database is already in latest version.
                    //
                    // Because of how the LNL pipeline works(it alwasy tests agains the develop branch), We have to apply 
                    // the fix from https://github.com/vegaprotocol/vega/pull/8662 again once data is in the database.
                
                    // This step MUST be removed once the vegaprotocol/vega#8662 is released to mainnet

                    print("Applies fix for https://github.com/vegaprotocol/vega/pull/8662")
                    script {
                        sh 'sudo apt-get update && sudo apt-get install -y postgresql-client-14'
                    }

                    for (int i=0; i<20; i++) {
                        catchError {
                            script {
                                sh '''PGPASSWORD=vega \
                                    psql --host localhost --port 5532 --user vega vega'''+ i + ''' \
                                         --command "delete from stake_linking where id='\\x6fb63c814ffe23b706decf5aeb0be88727b19618970655d5257c189454b4520f';"
                                '''

                                sh '''PGPASSWORD=vega \
                                    psql --host localhost --port 5532 --user vega vega'''+ i + ''' \
                                         --command "delete from stake_linking_current where id='\\x6fb63c814ffe23b706decf5aeb0be88727b19618970655d5257c189454b4520f';"
                                '''
                            }
                        }
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
