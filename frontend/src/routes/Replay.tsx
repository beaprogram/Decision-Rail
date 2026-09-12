import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { policyApi, replayApi } from '../api/endpoints';
import { useSession } from '../auth/session';
import { useIdempotentCommand } from '../lib/command';
import { PageHeader } from '../components/Shell';
import {
  Badge,
  Card,
  EmptyState,
  ErrorNotice,
  Field,
  Identifier,
  LoadingRows,
  Notice,
  ShortIdentifier,
  TableScroll,
  Timestamp,
} from '../components/ui';
import type { ReplayJob } from '../api/types';

export function JobStatusBadge({ status }: { status: ReplayJob['status'] }) {
  const tones = { PENDING: 'neutral', RUNNING: 'info', COMPLETED: 'success', FAILED: 'danger' } as const;
  return <Badge tone={tones[status]}>{status}</Badge>;
}

/** True while a job can still change, which is the only time polling is worth doing. */
export function isJobActive(status: ReplayJob['status']): boolean {
  return status === 'PENDING' || status === 'RUNNING';
}

export function ReplayPage() {
  const { can } = useSession();
  const queries = useQueryClient();

  const jobs = useQuery({
    queryKey: ['replay-jobs'],
    queryFn: ({ signal }) => replayApi.jobs(signal),
    // Bounded polling: only while something is unfinished, never in a background tab, and never
    // overlapping because the query is not refetched while a fetch is outstanding.
    refetchInterval: (query) => {
      const data = query.state.data;
      return data?.some((job) => isJobActive(job.status)) ? 2_000 : false;
    },
    refetchIntervalInBackground: false,
  });

  const policies = useQuery({
    queryKey: ['policies'],
    queryFn: ({ signal }) => policyApi.list(signal),
    enabled: can.createReplay,
  });

  const candidates = (policies.data ?? []).filter((policy) => policy.origin === 'CANDIDATE');

  return (
    <>
      <PageHeader
        title="Policy replay"
        description="Evaluates a candidate policy against payments that already happened, comparing it with the risk decision each payment actually recorded."
      />
      <div className="page-body">
        {can.createReplay && (
          <CreateReplayJob
            candidates={candidates.map((policy) => policy.versionId)}
            loadingCandidates={policies.isPending}
            onCreated={() => void queries.invalidateQueries({ queryKey: ['replay-jobs'] })}
          />
        )}

        <Card
          title="Jobs"
          scope="Your own replay jobs. Each one pinned its input set when it was created."
          actions={jobs.isFetching ? <span className="field-hint">Refreshing…</span> : undefined}
          tight
        >
          {jobs.isPending && <LoadingRows rows={3} label="Loading replay jobs" />}
          {/* An error after a successful load keeps the last good data on screen. */}
          {jobs.error && (
            <div className="card-body">
              <ErrorNotice error={jobs.error} context="Loading replay jobs" />
              {jobs.data && <span className="field-hint">Showing the last successful result below.</span>}
            </div>
          )}
          {jobs.data && jobs.data.length === 0 && (
            <EmptyState title="No replay jobs yet">
              <span>Create one above to compare a candidate policy against your payment history.</span>
            </EmptyState>
          )}
          {jobs.data && jobs.data.length > 0 && (
            <TableScroll>
              <table>
                <thead>
                  <tr>
                    <th scope="col">Job</th>
                    <th scope="col">Candidate</th>
                    <th scope="col">Status</th>
                    <th scope="col" className="numeric">Inputs</th>
                    <th scope="col" className="numeric">Done</th>
                    <th scope="col" className="numeric">Failed</th>
                    <th scope="col" className="numeric">Diverged</th>
                    <th scope="col">Created</th>
                  </tr>
                </thead>
                <tbody>
                  {jobs.data.map((job) => (
                    <tr key={job.id}>
                      <td>
                        <Link to={`/replay/${job.id}`}>
                          <ShortIdentifier value={job.id} />
                        </Link>
                      </td>
                      <td><Identifier value={job.candidateVersion} /></td>
                      <td>
                        <JobStatusBadge status={job.status} />
                        {isJobActive(job.status) && job.inputCount > 0 && (
                          <div className="id-short">
                            {job.completedCount + job.failedCount} of {job.inputCount}
                          </div>
                        )}
                      </td>
                      <td className="numeric">{job.inputCount}</td>
                      <td className="numeric">{job.completedCount}</td>
                      <td className="numeric">{job.failedCount}</td>
                      <td className="numeric">{job.divergenceCount}</td>
                      <td className="nowrap"><Timestamp value={job.createdAt} /></td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </TableScroll>
          )}
        </Card>
      </div>
    </>
  );
}

function CreateReplayJob({
  candidates,
  loadingCandidates,
  onCreated,
}: {
  candidates: string[];
  loadingCandidates: boolean;
  onCreated: () => void;
}) {
  const [candidateVersion, setCandidateVersion] = useState('');
  const [limit, setLimit] = useState('500');
  const [from, setFrom] = useState('');

  const create = useIdempotentCommand('replay', (key) =>
    replayApi.create(key, {
      candidateVersion,
      limit: Number(limit),
      ...(from ? { from: new Date(from).toISOString() } : {}),
    }),
  );

  const limitValue = Number(limit);
  const limitError =
    Number.isInteger(limitValue) && limitValue >= 1 && limitValue <= 5000
      ? null
      : 'Choose between 1 and 5000 inputs.';

  return (
    <Card
      title="New replay job"
      scope="Membership is fixed when the job is created, so payments made afterwards never join it."
    >
      <form
        className="stack"
        onSubmit={async (event) => {
          event.preventDefault();
          if (!candidateVersion || limitError) return;
          const created = await create.run(undefined);
          if (created) onCreated();
        }}
      >
        <div className="grid cols-3">
          <Field label="Candidate policy" hint="Candidates only. The authoritative policy is the baseline.">
            {(props) => (
              <select
                {...props}
                value={candidateVersion}
                onChange={(event) => setCandidateVersion(event.target.value)}
                required
              >
                <option value="">{loadingCandidates ? 'Loading…' : 'Select a candidate'}</option>
                {candidates.map((version) => (
                  <option key={version} value={version}>
                    {version}
                  </option>
                ))}
              </select>
            )}
          </Field>
          <Field label="Maximum inputs" hint="Bounded membership, 1 to 5000." error={limitError}>
            {(props) => (
              <input {...props} type="number" min={1} max={5000} value={limit} onChange={(event) => setLimit(event.target.value)} />
            )}
          </Field>
          <Field label="Created from" hint="Optional earliest payment time. The latest is always job creation.">
            {(props) => <input {...props} type="datetime-local" value={from} onChange={(event) => setFrom(event.target.value)} />}
          </Field>
        </div>

        {candidates.length === 0 && !loadingCandidates && (
          <Notice tone="info" title="No candidate policies are registered yet">
            An administrator registers candidates on the policy versions screen. Replay needs a candidate
            to compare against the stored decisions.
          </Notice>
        )}

        <div className="row">
          <button
            type="submit"
            className="primary"
            disabled={!candidateVersion || Boolean(limitError) || create.busy}
          >
            {create.busy ? 'Creating…' : 'Create job'}
          </button>
        </div>
      </form>

      {create.state.phase === 'uncertain' && (
        <Notice tone="warning" title="The job may or may not have been created">
          <span>{create.state.error.detail}</span>
          <div className="row">
            <button type="button" onClick={() => void create.retry()}>
              Retry safely
            </button>
          </div>
        </Notice>
      )}
      {create.state.phase === 'failed' && <ErrorNotice error={create.state.error} context="Creating the replay job" />}
      {create.state.phase === 'succeeded' && (
        <Notice tone="success" title="Job created">
          <span>
            Pinned {create.state.result.inputCount} input
            {create.state.result.inputCount === 1 ? '' : 's'}.{' '}
            <Link to={`/replay/${create.state.result.id}`}>Open the job</Link>
          </span>
        </Notice>
      )}
    </Card>
  );
}
