/* groovylint-disable LineLength */
void call(Map paramsOverrides=[:]) {
    List mainnetApiServers = [
      'api0.vega.community',
      'api1.vega.community',
      'api2.vega.community',
    ]

    capsuleSystemTests([
        agentLabel: params.NODE_LABEL ?: '',
        vegacapsuleConfig: 'capsule_config_mainnet_snapshot.hcl',
        systemTestsBranch: 'lnl-pipeline',
        extraEnvVars: [
            'MAINNET_TEST_CASE': 'true',
        ],
        fastFail: false,
        slackTitle: 'LNL Mainnet System Tests',
        hooks: [
            postNetworkGenerate: [
                'Download core snapshot from mainnet API': {
                    // TBD
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
    ], paramsOverrides)
}
