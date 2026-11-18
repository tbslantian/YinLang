package app.soundshelf;

import android.app.job.JobParameters;
import android.app.job.JobService;

public final class RefreshJob extends JobService {
    private final java.util.Map<Integer, JobParameters> jobs = new java.util.HashMap<>();

    @Override public boolean onStartJob(JobParameters params) {
        jobs.put(params.getJobId(), params);
        if (params.getJobId() == PlaylistRefresh.JOB) {
            PlaylistRefresh.get(this).automaticRefresh(report -> finish(params, report.updated() == 0 && report.failed() > 0));
        } else SourceUpdates.get(this).check(false, ignored -> finish(params, false));
        return true;
    }
    private void finish(JobParameters params, boolean retry) {
        if (!jobs.remove(params.getJobId(), params)) return;
        jobFinished(params, retry);
    }
    @Override public boolean onStopJob(JobParameters params) {
        jobs.remove(params.getJobId(), params);
        return true;
    }
}
