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
    devopsInfraBranch: 'master',
    vegatoolsBranch: 'develop',
    networksBranch: 'master',
    checkpointStoreBranch: 'main',
    systemTestsBranch: 'develop',

    vegaBuildTags: '',

    validatorNodeCount: '2',
    nonValidatorNodeCount: '1',

    mainnet: false,
    genesisJSON: '',
    mainnetGenesis: 'system-tests/tests/LNL/mainnet/genesis.json',
    checkpoint: '',
    legacyResume: false,
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
    testDirectory: '',
    testFunction: '',
    testMark:'smoke',
    protosBranch: 'develop',
    systemTestsDebug: false,
    genesisJSON: 'system-tests/docker/zero-genesis.json',
]

@Field
Map capsuleSystemTests = [
    branchDevopsInfra: 'master',
    branchVegaCapsule: 'main',
    branchVega: 'develop',
    branchDataNode: 'develop',
    branchSystemTests: 'develop',
    branchVegawallet: 'develop',
    branchProtos: 'develop',
    branchVegatools: 'develop',

    systemTestsTestFunction: '',
    systemTestsTestMark: 'smoke',
    systemTestsTestDirectory: '',
    systemTestsDebug: false,
    systemTestsRunTimeout: '60',
]

// System Tests LNL pipeline
@Field
Map lnl = st + [
    testDirectory: 'tests/LNL',
    testFunctionCreate: 'create_data_test',
    testFunctionAssert: 'assert_data_test'
]

// Private Network pipeline
@Field
Map pn = dv + [
    legacyResume: true,
]

// Mainnet checkpoint test pipeline
@Field
Map mnnt = st + [
    testDirectory: 'tests/LNL',
    testFunction: 'test_checkpoint_loaded',
    testMark: '',
    afterLoadCheckpoint: "system-tests/tests/LNL/after_checkpoint_load.json",
    legacyResume: true,
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
    createIncentiveMarkets: true,
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
Map fair = [
    devopsInfraBranch: 'master'
]

// Approbation pipeline
@Field
Map appr = [
    vegaCoreBranch: 'develop',
    specsInternalBranch: 'master',
    multisigControlBranch: 'develop',
    systemTestsBranch: 'develop',
    specsArg: '{./specs-internal/protocol/**/*.{md,ipynb},./specs-internal/non-protocol-specs/**/*.{md,ipynb}}',
    testsArg: '{./system-tests/tests/**/*.py,./vega/integration/**/*.{go,feature},./MultisigControl/test/*.js}',
    ignoreArg: '{./spec-internal/protocol/0060*,./specs-internal/non-protocol-specs/{0001-NP*,0002-NP*,0004-NP*,0006-NP*,0007-NP*,0008-NP*,0010-NP*}}',
    otherArg: '--show-branches --show-mystery --category-stats --show-files --verbose --output-csv --output-jenkins',
    approbationVersion: '2.4.2'
]

@Field
Map art = [
    systemTestCapsuleJunit: 'build/test-reports/system-test-results.xml',
    systemTestsJunit: 'output/junit-report/system-tests.xml',
    systemTestsLogs: 'output/test_logs',
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
