/* groovylint-disable
  BuilderMethodWithSideEffects, CompileStatic, DuplicateStringLiteral,
  FactoryMethodName, VariableTypeRequired */
import org.jenkinsci.plugins.workflow.support.steps.build.RunWrapper

void call(Map config = [:]) {
  Boolean ignoreFailure = config.ignoreFailure ? "${config.ignoreFailure}".toBoolean() : false
  String systemTestsNBCJob = '/common/system-tests-nbc'

  List buildParameters = [
      string(name: 'TIMEOUT', value: config.timeout ? "${config.timeout}" : "30"),
      booleanParam(
          name: 'RUN_EXTRA_TESTS',
          value: config.runExtraTests ? "${config.runExtraTests}".toBoolean() : false
      ),
      string(name: 'ORIGIN_REPO', value: config.originRepo ?: 'vegaprotocol/vega'),
      string(name: 'VEGA_VERSION', value: config.vegaVersion ?: "develop"),
      string(name: 'VEGACAPSULE_VERSION', value: config.vegacapsuleVersion ?: "main"),
      string(name: 'SYSTEM_TESTS_NBC_BRANCH', value: config.systemTestsNBC ?: "develop"),
      string(name: 'JENKINS_SHARED_LIB_BRANCH', value: config.jenkinsSharedLib ?: "main"),
      string(name: 'NODE_LABEL', value: config.nodeLabel ?: 'vega-market-sim'),
      booleanParam(
        name: "BRANCH_RUN",
        value: config.branchRun ? "${config.branchRun}".toBoolean() : false
      ),
      string(name: "PARALLEL_WORKERS", value: config.parallelWorkers ?: "1"),
  ]

   RunWrapper vms = build(
      job: systemTestsNBCJob,
      propagate: false,  // don't fail yet
      wait: true,
      parameters: buildParameters)

    try {
      echo "System-Tests NBC test execution pipeline: ${vms.absoluteUrl}"
    } catch (e) {
      echo "Ignoring error in gathering results from downstream build: ${e}"
    }

    // now fail
    if (vms.result != 'SUCCESS') {
      if (ignoreFailure) {
        catchError(message: 'System-Tests NBC Failed', buildResult: null, stageResult: vms.result) {
            error("Ignore failure and keep job green, but mark stage as ${vms.result}")
        }
      } else {
        if (vms.result == 'UNSTABLE') {
            unstable('UNSTABLE - System-Tests NBC')
        } else if (vms.result == 'ABORTED') {
            currentBuild.result = 'ABORTED'
            error('ABORTED - System-Tests NBC')
        } else {
            error("${vms.result} - System-Tests NBC")
        }
      }
    }

}
