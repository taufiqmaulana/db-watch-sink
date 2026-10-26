package io.datasync.core;

/**
 * POLL is a job's regular run: a full snapshot diff for SNAPSHOT_DIFF jobs, a watermark read otherwise.
 * RECONCILE is always a full snapshot diff, the only run that detects deletes in WATERMARK and HYBRID jobs.
 */
public enum RunKind { POLL, RECONCILE }
