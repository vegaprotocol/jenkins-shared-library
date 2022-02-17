import org.jenkinsci.plugins.pipeline.modeldefinition.Utils

void call(Map config=[:], Closure body=null) {
    String stageName = config.name
    Boolean when = config.when ? "${config.when}".toBoolean() : false
    stage(stageName) {
        if (when) {
            body()
        } else {
            echo "Skip stage: ${stageName}"
            Utils.markStageSkippedForConditional(stageName)
        }
    }
}
