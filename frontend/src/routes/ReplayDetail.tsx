import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { replayApi } from '../api/endpoints';
import { PageHeader } from '../components/Shell';
import {
  Badge,
  Card,
  EmptyState,
  ErrorNotice,
  Identifier,
  KeyValues,
  LoadingRows,
  Notice,
  Reasons,
  RiskBadge,
  ScoreDisplay,
  ShortIdentifier,
  Stat,
  Timestamp,
} from '../components/ui';
import { isJobActive, JobStatusBadge } from './Replay';

const RESULTS_PAGE = 20;

export function ReplayDetailPage() {
  const { jobId = '' } = useParams();
  const [divergedOnly, setDivergedOnly] = useState(false);
  const [offset, setOffset] = useState(0);

  const job = useQuery({
    queryKey: ['replay-job', jobId],
    queryFn: ({ signal }) => replayApi.job(jobId, signal),
    // Polls only while the job can still change, and stops as soon as it cannot.
    refetchInterval: (query) => (query.state.data && isJobActive(query.state.data.status) ? 1_500 : false),
    refetchIntervalInBackground: false,
  });

  const finished = job.data && !isJobActive(job.data.status);

  // The job's status is part of these query keys on purpose. Polling alone is not enough: the interval
  // stops the moment the job finishes, so without a key change the last fetched report would be the one
  // taken while the job was still running, and a completed job would display empty counts forever.
  const jobStatus = job.data?.status;

  const report = useQuery({
    queryKey: ['replay-job', jobId, 'report', jobStatus],
    queryFn: ({ signal }) => replayApi.report(jobId, signal),
    enabled: Boolean(jobStatus),
    refetchInterval: () => (jobStatus && isJobActive(jobStatus) ? 2_000 : false),
    refetchIntervalInBackground: false,
  });

  const results = useQuery({
    queryKey: ['replay-job', jobId, 'results', jobStatus, divergedOnly, offset],
    queryFn: ({ signal }) => replayApi.results(jobId, divergedOnly, RESULTS_PAGE, offset, signal),
    enabled: Boolean(finished) || (job.data?.completedCount ?? 0) > 0,
  });

  if (job.isPending) {
    return (
      <>
        <PageHeader title="Replay job" />
        <div className="page-body">
          <LoadingRows rows={5} label="Loading replay job" />
        </div>
      </>
    );
  }
  if (job.error || !job.data) {
    return (
      <>
        <PageHeader title="Replay job" />
        <div className="page-body">
          <ErrorNotice error={job.error} context="Loading the replay job" />
          <Link to="/replay">Back to replay jobs</Link>
        </div>
      </>
    );
  }

  const current = job.data;
  const progressed = current.completedCount + current.failedCount;

  return (
    <>
      <PageHeader
        title="Replay job"
        badge={<JobStatusBadge status={current.status} />}
        description={<Identifier value={current.id} />}
        actions={
          <Link to="/replay">
            <button type="button">Back</button>
          </Link>
        }
      />
      <div className="page-body">
        {current.status === 'FAILED' && (
          <Notice tone="danger" title="This job failed">
            <span>{current.failureDetail ?? 'No detail was recorded.'}</span>
          </Notice>
        )}

        <div className="grid cols-4">
          <Stat
            label="Pinned inputs"
            value={current.inputCount}
            note="Fixed when the job was created. Later payments never join."
          />
          <Stat label="Evaluated" value={current.completedCount} note={`${progressed} of ${current.inputCount} processed`} />
          <Stat label="Failed to evaluate" value={current.failedCount} note="Recorded with a reason, not skipped" />
          <Stat label="Not yet evaluated" value={current.pendingCount} />
        </div>

        {report.isPending && <LoadingRows rows={3} label="Loading report" />}
        {report.error && <ErrorNotice error={report.error} context="Loading the report" />}
        {report.data && (
          <>
            <Card
              title="Comparison report"
              scope="Candidate risk outcomes against the risk decision each payment actually recorded. Payment status is not the baseline."
            >
              <div className="grid cols-3">
                <Stat
                  label="Diverged"
                  value={report.data.divergenceCount}
                  note="Candidate risk outcome differs from the stored one"
                />
                {report.data.divergenceRate === null ? (
                  <Stat
                    label="Divergence rate"
                    unavailable="Not available"
                    note="Nothing has been evaluated yet, so there is no denominator."
                  />
                ) : (
                  <Stat
                    label="Divergence rate"
                    value={`${(report.data.divergenceRate * 100).toFixed(2)}%`}
                    note={report.data.divergenceDenominator}
                  />
                )}
                {report.data.evaluationTimings.meanNanos === null ? (
                  <Stat label="Mean evaluation time" unavailable="Not available" note="No evaluation has been timed." />
                ) : (
                  <Stat
                    label="Mean evaluation time"
                    value={`${(report.data.evaluationTimings.meanNanos / 1000).toFixed(1)} µs`}
                    note={`Observed over ${report.data.evaluationTimings.measuredEvaluations} evaluations, not a benchmark`}
                  />
                )}
              </div>

              <div className="grid cols-2">
                <div className="stack tight">
                  <span className="field-label">Candidate outcomes</span>
                  <OutcomeCounts counts={report.data.candidateOutcomeCounts} />
                </div>
                <div className="stack tight">
                  <span className="field-label">Baseline outcomes (stored decisions)</span>
                  <OutcomeCounts counts={report.data.baselineOutcomeCounts} />
                </div>
              </div>

              <KeyValues
                entries={[
                  ['Candidate', <Identifier value={report.data.candidateVersion} />],
                  ['Candidate hash', <Identifier value={report.data.candidateHash} />],
                  ['Baseline policy', report.data.baselinePolicyVersion ?? 'not recorded'],
                  ['Baseline source', report.data.baselineSource.replace(/_/g, ' ').toLowerCase()],
                  ['Window', <span>{report.data.windowFrom ? <Timestamp value={report.data.windowFrom} /> : 'from the beginning'} to <Timestamp value={report.data.windowTo} /></span>],
                  ['Completed at', report.data.completedAt ? <Timestamp value={report.data.completedAt} /> : 'still running'],
                ]}
              />

              <Notice tone="info" title="How to read these figures">
                <span>{report.data.divergenceDenominator}</span>
                <span>{report.data.timingMethod}</span>
                {!report.data.labelledOutcomeDataAvailable && (
                  <span>
                    No labelled fraud outcomes exist for this synthetic data, so no accuracy, precision or
                    prevented-loss figure is computed anywhere in this report.
                  </span>
                )}
              </Notice>
            </Card>

            <Card
              title="Per-payment comparison"
              scope={divergedOnly ? 'Only inputs where the candidate disagreed.' : 'Every evaluated input.'}
              actions={
                <label className="row" style={{ gap: 'var(--space-2)' }}>
                  <input
                    type="checkbox"
                    checked={divergedOnly}
                    onChange={(event) => {
                      setDivergedOnly(event.target.checked);
                      setOffset(0);
                    }}
                  />
                  <span>Diverged only</span>
                </label>
              }
            >
              {results.isPending && <LoadingRows rows={3} label="Loading results" />}
              {results.error && <ErrorNotice error={results.error} context="Loading results" />}
              {results.data && results.data.length === 0 && (
                <EmptyState title={divergedOnly ? 'No divergences in this page' : 'No results recorded yet'} />
              )}
              {results.data?.map((result) => (
                <div className="stack tight" key={result.paymentId}>
                  <div className="row between">
                    <Link to={`/payments/${result.paymentId}`}>
                      <ShortIdentifier value={result.paymentId} />
                    </Link>
                    <div className="row" style={{ gap: 'var(--space-2)' }}>
                      {result.diverged ? <Badge tone="warning">diverged</Badge> : <Badge tone="success">agreed</Badge>}
                      <span className="id-short">
                        payment was {result.paymentStatus}
                        {result.paymentFailureCode ? ` · ${result.paymentFailureCode}` : ''}
                      </span>
                    </div>
                  </div>
                  <div className="comparison">
                    <div className="comparison-side">
                      <span className="comparison-label">Baseline · stored risk decision</span>
                      <div className="row">
                        <RiskBadge outcome={result.baselineOutcome} />
                        <ScoreDisplay score={result.baselineScore} />
                      </div>
                      <Reasons reasons={result.baselineReasons} />
                    </div>
                    <div className={result.diverged ? 'comparison-side diverged' : 'comparison-side'}>
                      <span className="comparison-label">Candidate · {report.data.candidateVersion}</span>
                      {result.errorCode ? (
                        <Notice tone="warning" title="The candidate could not evaluate this input">
                          <span>{result.errorCode}</span>
                        </Notice>
                      ) : (
                        <>
                          <div className="row">
                            <RiskBadge outcome={result.candidateOutcome} />
                            <ScoreDisplay
                              score={result.candidateScore}
                              rawScore={result.candidateRawScore}
                              capped={result.candidateScoreCapped}
                            />
                          </div>
                          {result.candidateScoreCapped && result.candidateRawScore !== null && (
                            <span className="field-hint">
                              Contributions add up to {result.candidateRawScore}. The score used for the
                              outcome is capped at 100, and the reasons below still reconcile to{' '}
                              {result.candidateRawScore}.
                            </span>
                          )}
                          <Reasons reasons={result.candidateReasons} />
                        </>
                      )}
                    </div>
                  </div>
                </div>
              ))}
              {results.data && (
                <div className="row between">
                  <span className="field-hint">
                    Showing {offset + 1}–{offset + results.data.length}
                  </span>
                  <div className="row">
                    <button type="button" onClick={() => setOffset(Math.max(0, offset - RESULTS_PAGE))} disabled={offset === 0}>
                      Previous
                    </button>
                    <button
                      type="button"
                      onClick={() => setOffset(offset + RESULTS_PAGE)}
                      disabled={results.data.length < RESULTS_PAGE}
                    >
                      Next
                    </button>
                  </div>
                </div>
              )}
            </Card>
          </>
        )}
      </div>
    </>
  );
}

function OutcomeCounts({ counts }: { counts: Record<string, number> }) {
  const order = ['APPROVE', 'REVIEW', 'DECLINE'];
  return (
    <div className="row" style={{ gap: 'var(--space-3)' }}>
      {order.map((outcome) => (
        <div className="row" key={outcome} style={{ gap: 'var(--space-2)' }}>
          <RiskBadge outcome={outcome} />
          <strong style={{ fontVariantNumeric: 'tabular-nums' }}>{counts[outcome] ?? 0}</strong>
        </div>
      ))}
    </div>
  );
}
