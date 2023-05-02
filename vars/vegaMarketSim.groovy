/* groovylint-disable
  BuilderMethodWithSideEffects, CompileStatic, DuplicateStringLiteral,
  FactoryMethodName, VariableTypeRequired */
import org.jenkinsci.plugins.workflow.support.steps.build.RunWrapper

void call(Map config = [:]) {
  Boolean ignoreFailure = config.ignoreFailure ? "${config.ignoreFailure}".toBoolean() : false
  String vegaMarketSimJob = '/common/vega-market-sim'

  List buildParameters = [
      string(name: 'TIMEOUT', value: config.timeout ? "${config.timeout}" : "30"),
      booleanParam(
          name: 'RUN_EXTRA_TESTS',
          value: config.runExtraTests ? "${config.runExtraTests}".toBoolean() : false
      ),
      string(name: 'ORIGIN_REPO', value: config.originRepo ?: 'vegaprotocol/vega'),
      string(name: 'VEGA_VERSION', value: config.vegaVersion ?: "develop"),
      string(name: 'VEGA_MARKET_SIM_BRANCH', value: config.vegaMarketSim ?: "develop"),
      string(name: 'JENKINS_SHARED_LIB_BRANCH', value: config.jenkinsSharedLib ?: "main"),
      string(name: 'NODE_LABEL', value: config.nodeLabel ?: 'system-tests'),
  ]

   RunWrapper vms = build(
      job: vegaMarketSimJob,
      propagate: false,  // don't fail yet
      wait: true,
      parameters: buildParameters)

    try {
      echo "Vega Market Sim test execution pipeline: ${vms.absoluteUrl}"
    } catch (e) {
      echo "Ignoring error in gathering results from downstream build: ${e}"
    }

    // now fail
    if (vms.result != 'SUCCESS') {
      if (ignoreFailure) {
        catchError(message: 'Vega Market Sim Failed', buildResult: null, stageResult: vms.result) {
            error("Ignore failure and keep job green, but mark stage as ${vms.result}")
        }
      } else {
        if (vms.result == 'UNSTABLE') {
            unstable('UNSTABLE - Vega Market Sim')
        } else if (vms.result == 'ABORTED') {
            currentBuild.result = 'ABORTED'
            error('ABORTED - Vega Market Sim')
        } else {
            error("${vms.result} - Vega Market Sim")
        }
      }
    }

}
