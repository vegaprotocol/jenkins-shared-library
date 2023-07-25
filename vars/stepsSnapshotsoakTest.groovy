// Snapshot SOAK Test Steps
//
// This file outlines the steps for the 'Snapshot SOAK Test'. It should be included
// and executed within another Jenkins Job that provides a dedicated node for
// running the specified steps.

void call(String testNetDirectory, String vegaBinaryPath) {
    script {
        print('''Running scenario for 1 suit case at the time for for base path "''' + DIR + '''"''')
        def nodeName = params.SUIT_NAME.contains('network_infra') ? 'node5' : 'node2'
        def tmHome = "tendermint/${nodeName}"
        def vegaHome = "vega/${nodeName}"
        def vegaBinary = '../vega'
        dir(DIR) {
            // prepare venv
            // generate all of the snapshots by replaying the whole chain
            // now load from all of the snapshots
            sh """
                ls -al tendermint
                ls -al vega
                . ${env.WORKSPACE}/venv/bin/activate
                ${env.WORKSPACE}/snapshot-soak-test --tm-home='${tmHome}' --vega-home='${vegaHome}' --vega-binary='${vegaBinary}' --replay
                ${env.WORKSPACE}/snapshot-soak-test --tm-home='${tmHome}' --vega-home='${vegaHome}' --vega-binary='${vegaBinary}'
            """
        }
    }
}
