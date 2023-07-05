/* groovylint-disable LineLength */
void call(Map paramsOverrides=[:]) {
    List mainnetApiServers = [
      'api0.vega.community',
      'api1.vega.community',
      'api2.vega.community',
    ]

    capsuleSystemTests([
        agentLabel: params.NODE_LABEL ?: '',
        systemTestsBranch: 'lnl-pipeline',
        extraEnvVars: [
            'MAINNET_TEST_CASE': 'true',
        ],
        fastFail: false,
        slackTitle: 'LNL Mainnet System Tests',
        hooks: [
            postStartNomad: [
                'Download mainnet genesis': {
                    sh '''
                        rm -f "''' + WORKSPACE + '''/system-tests/vegacapsule/net_configs/mainnet_snapshot/genesis.json" \
                            || echo "old genesis does not exists";
                        
                        wget https://raw.githubusercontent.com/vegaprotocol/networks/master/mainnet1/genesis.json \
                        --tries 3 \
                        --output-document "''' + WORKSPACE + '''/system-tests/vegacapsule/net_configs/mainnet_snapshot/genesis.json";
                    '''
                }
            ],
            postNetworkGenerate: [
                'Download core snapshot from mainnet API': {
                    script {
                        // sh '''rsync \
                        //     --archive \
                        //     --verbose \
                        //     --compress \
                        //     -e 'ssh -i $KEY -o StrictHostKeyChecking=no' \
                        //     $USER@'''+ mainnerServer + ''':/home/vega/vega_home/state/node/snapshots \
                        //     ./api-snapshots
                        // '''
                    }
                }
            ],
            runTests: [
                'Copy snapshot snapshot produced by local network': {
                   
                }
            ],
            postRunTests: [
              
            ],
            postPipeline: [
               
            ]
        ],
    ], [
        CAPSULE_CONFIG: 'capsule_config_mainnet_snapshot.hcl',
    ])
}
