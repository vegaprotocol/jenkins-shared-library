/* groovylint-disable DuplicateStringLiteral */
/*
 * Jenkins sometimes ignores default paramter values and uses values from the previous build
 * To avoid that flaky behaviour, the default parameter values are set explicitly in multiple places
 */
import groovy.transform.Field

// Dockerised Vega pipeline
@Field
Map dv = [
    vegaCoreBranch: '', // 'develop',
    dataNodeBranch: '', // 'develop',
    vegaWalletBranch: '', // 'develop',
    ethereumEventForwarderBranch: '', // 'main',
    devopsInfraBranch: 'master',
    vegatoolsBranch: 'develop',
    networksBranch: 'master',
    checkpointStoreBranch: 'main',

    validatorNodeCount: '2',
    nonValidatorNodeCount: '1',

    mainnet: false,
    genesisJSON: '',
    checkpoint: '',
    ethEndpointUrl: '',

    tendermintLogLevel: 'info',
    vegaCoreLogLevel: 'Info',
    vegaCoreDLV: false,

    agent: 'system-tests',
    timeout: '100'
]

// System Tests pipeline
@Field
Map st = dv + [
    testDirectory: 'CoreTesting/bvt',
    testFunction: '',
    testMark:'',
    protosBranch: 'develop',
    systemTestsBranch: 'develop',
    systemTestsDebug: false,
    genesisJSON: 'system-tests/docker/zero-genesis.json'
]

// System Tests LNL pipeline
@Field
Map lnl = st + [
    testDirectory: 'LNL',
    testFunctionCreate: 'create_data',
    testFunctionAssert: 'assert_data',
    testLnlLoadTimeout: ''
]

@Field
Map restartOptions = [
    restartOnly: 'Restart network',
    restartFromCheckpoint: 'Restart from checkpoint',
    dontRestart: 'Don\'t restart'
]

@Field
Map dev = [
    vegaCoreVersion: '',
    deployConfig: true,
    restart: restartOptions.restartOnly,
    createMarkets: true,
    bounceBots: true,
    devopsInfraBranch: 'master'
]

@Field
Map stag = [
    reason: 'I want to restart the Stagnet, because ...',
    deployVegaCore: '',
    deployConfig: true,
    restartNetwork: true,
    devopsInfraBranch: 'master'
]

@Field
Map art = [
    systemTestsJunit: 'output/junit-report/system-tests.xml',
    checkpointEnd: 'output/network/checkpoint-at-the-end.json',
    resumeCheckpoint: 'output/network/checkpoint-resume.json',
    genesis: 'output/network/genesis.json',
    genesisRestore: 'output/network/genesis-restore.json',
    lnl: [
        systemTestsCreateState: 'output/lnl/system-tests-1-create-state.xml',
        systemTestsAssertState: 'output/lnl/system-tests-2-assert-state.xml',
        checkpointRestore: 'output/lnl/checkpoint-1-restore.json',
        checkpointEnd: 'output/lnl/checkpoint-2-end.json',
        systemTestsState: 'output/system-tests-lnl-state',
    ],
    systemTestsState: 'output/system-tests-state',
]

return this
