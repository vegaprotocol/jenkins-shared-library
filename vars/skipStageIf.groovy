
void call(Boolean condition, Closure body=null) {
    if (condition) {
            Utils.markStageSkippedForConditional(STAGE_NAME)
    } else {
        body()
    }
}
