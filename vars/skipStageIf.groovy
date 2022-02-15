import org.jenkinsci.plugins.pipeline.modeldefinition.Utils

void call(Boolean condition, Closure body=null) {
    if (condition) {
            Utils.markStageSkippedForConditional(STAGE_NAME)
    } else {
        body()
    }
}
