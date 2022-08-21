/* groovylint-disable 
  BuilderMethodWithSideEffects, CompileStatic, DuplicateStringLiteral, 
  FactoryMethodName, VariableTypeRequired */
import org.jenkinsci.plugins.workflow.support.steps.build.RunWrapper

void call(Map config = [:]) {
  Boolean ignoreFailure = config.ignoreFailure ? "${config.ignoreFailure}".toBoolean() : false
  String vegaMarketSimJob = '/common/vega-market-sim'

  List buildParameters = [
      string(name: 'TIMEOUT', value: config.timeout ? "${config.timeout}" : "30"),
      
      string(name: 'VEGA_VERSION', value: config.vegaVersion ?: "develop"),
      string(name: 'VEGA_MARKET_SIM_BRANCH', value: config.vegaMarketSim ?: "develop"),
      string(name: 'JENKINS_SHARED_LIB_BRANCH', value: config.jenkinsSharedLib ?: "main"),
  ]

   RunWrapper vms = build(
      job: vegaMarketSimJob,
      propagate: false,  // don't fail yet
      wait: true,
      parameters: buildParameters)

    try {
      echo "Vega Market SIM test execution pipeline: ${vms.absoluteUrl}"
    } catch (e) {
      echo "Ignoring error in gathering results from downstream build: ${e}"
    }

    // now fail
    if (vms.result != 'SUCCESS') {
      if (ignoreFailure) {
        catchError(message: 'Vega Market SIM Failed', buildResult: null, stageResult: vms.result) {
            error("Ignore failure and keep job green, but mark stage as ${vms.result}")
        }
      } else {
        if (vms.result == 'UNSTABLE') {
            unstable('UNSTABLE - Vega Market SIM')
        } else if (vms.result == 'ABORTED') {
            currentBuild.result = 'ABORTED'
            error('ABORTED - Vega Market SIM')
        } else {
            error("${vms.result} - Vega Market SIM")
        }
      }
    }

}
