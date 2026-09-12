import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { merchantApi, opsApi, policyApi } from '../api/endpoints';
import { useSession } from '../auth/session';
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
  Reasons,
  RiskBadge,
  ScoreDisplay,
  ShortIdentifier,
  Stat,
  Timestamp,
} from '../components/ui';

/**
 * Shadow evaluation.
 *
 * Merchants see what a candidate would have decided for their own authorizations. Administrators
 * additionally configure which candidate is observed. The two are separate concerns and separate
 * permissions, so they appear as separate cards rather than one mixed control.
 */
export function ShadowPage() {
  const { can } = useSession();
  const [divergedOnly, setDivergedOnly] = useState(true);

  const comparisons = useQuery({
    queryKey: ['shadow-comparisons', divergedOnly],
    queryFn: ({ signal }) => merchantApi.shadowComparisons(divergedOnly, 25, signal),
    enabled: can.viewShadowComparisons,
  });

  return (
    <>
      <PageHeader
        title="Shadow evaluation"
        description="Runs a candidate policy alongside live decisions, after the fact, and records what it would have decided. It never changes a decision and never reserves or releases funds."
      />
      <div className="page-body">
        {can.configureShadow && <ShadowConfiguration />}

        {can.viewShadowComparisons && (
          <Card
            title="Recorded comparisons"
            scope="Your own payments, most recent first. Only authorizations made while shadow evaluation was enabled appear here."
            actions={
              <label className="row" style={{ gap: 'var(--space-2)' }}>
                <input
                  type="checkbox"
                  checked={divergedOnly}
                  onChange={(event) => setDivergedOnly(event.target.checked)}
                />
                <span>Diverged only</span>
              </label>
            }
          >
            {comparisons.isPending && <LoadingRows rows={3} label="Loading comparisons" />}
            {comparisons.error && <ErrorNotice error={comparisons.error} context="Loading shadow comparisons" />}
            {comparisons.data && comparisons.data.length === 0 && (
              <EmptyState title={divergedOnly ? 'No divergences recorded' : 'No comparisons recorded'}>
                <span>
                  Shadow evaluation only observes authorizations made while it is enabled. It does not
                  backfill earlier payments, so use a <Link to="/replay">replay job</Link> for history.
                </span>
              </EmptyState>
            )}
            {comparisons.data?.map((comparison) => (
              <div className="stack tight" key={`${comparison.candidateVersion}-${comparison.paymentId}`}>
                <div className="row between">
                  <Link to={`/payments/${comparison.paymentId}`}>
                    <ShortIdentifier value={comparison.paymentId} />
                  </Link>
                  <div className="row" style={{ gap: 'var(--space-2)' }}>
                    {comparison.errorCode ? (
                      <Badge tone="danger">candidate failed</Badge>
                    ) : comparison.diverged ? (
                      <Badge tone="warning">diverged</Badge>
                    ) : (
                      <Badge tone="success">agreed</Badge>
                    )}
                    <span className="id-short">
                      <Timestamp value={comparison.evaluatedAt} />
                    </span>
                  </div>
                </div>
                <div className="comparison">
                  <div className="comparison-side">
                    <span className="comparison-label">Live decision · applied to the payment</span>
                    <div className="row">
                      <RiskBadge outcome={comparison.baselineOutcome} />
                      <ScoreDisplay score={comparison.baselineScore} />
                    </div>
                    <Reasons reasons={comparison.baselineReasons} />
                  </div>
                  <div className={comparison.diverged ? 'comparison-side diverged' : 'comparison-side'}>
                    <span className="comparison-label">
                      Candidate {comparison.candidateVersion} · observation only
                    </span>
                    {comparison.errorCode ? (
                      <Notice tone="warning" title="The candidate threw while evaluating">
                        <span>{comparison.errorCode}</span>
                        <span>The live decision was unaffected.</span>
                      </Notice>
                    ) : (
                      <>
                        <div className="row">
                          <RiskBadge outcome={comparison.candidateOutcome} />
                          <ScoreDisplay
                            score={comparison.candidateScore}
                            rawScore={comparison.candidateRawScore}
                            capped={comparison.candidateScoreCapped}
                          />
                        </div>
                        <Reasons reasons={comparison.candidateReasons} />
                      </>
                    )}
                  </div>
                </div>
              </div>
            ))}
          </Card>
        )}
      </div>
    </>
  );
}

function ShadowConfiguration() {
  const queries = useQueryClient();
  const [candidate, setCandidate] = useState('');

  const settings = useQuery({ queryKey: ['shadow-settings'], queryFn: ({ signal }) => opsApi.shadowSettings(signal) });
  const policies = useQuery({ queryKey: ['policies'], queryFn: ({ signal }) => policyApi.list(signal) });
  const candidates = (policies.data ?? []).filter((policy) => policy.origin === 'CANDIDATE');

  const configure = useMutation({
    mutationFn: ({ enabled, version }: { enabled: boolean; version: string | null }) =>
      opsApi.configureShadow(enabled, version),
    onSuccess: () => void queries.invalidateQueries({ queryKey: ['shadow-settings'] }),
  });

  const current = settings.data;
  const selected = candidate || current?.candidateVersion || '';

  return (
    <Card
      title="Configuration"
      scope="Administrative. Selecting a candidate here grants it no authority over any payment."
    >
      {settings.isPending && <LoadingRows rows={2} label="Loading shadow configuration" />}
      {settings.error && <ErrorNotice error={settings.error} context="Loading shadow configuration" />}

      {current && (
        <>
          <div className="grid cols-4">
            <Stat
              label="State"
              value={current.enabled ? <Badge tone="success">Enabled</Badge> : <Badge tone="neutral">Disabled</Badge>}
              note={current.candidateVersion ? `Candidate ${current.candidateVersion}` : 'No candidate selected'}
            />
            <Stat label="Queued evaluations" value={current.pendingTasks} note="Durable work derived from events" />
            <Stat label="Failed evaluations" value={current.failedTasks} />
            <Stat
              label="Comparisons recorded"
              value={current.comparisons}
              note={`${current.divergences} diverged`}
            />
          </div>

          <Notice tone="info" title="What enabling this does and does not do">
            <span>
              Enabling a candidate starts evaluating it against new authorizations as their events are
              delivered. It does not promote the candidate, does not change any decision, and does not
              backfill payments that already happened. For history, create a replay job instead.
            </span>
          </Notice>

          <form
            className="row"
            onSubmit={(event) => {
              event.preventDefault();
              configure.mutate({ enabled: true, version: selected || null });
            }}
          >
            <Field label="Candidate policy" hint="Required to enable. Candidates only.">
              {(props) => (
                <select {...props} value={selected} onChange={(event) => setCandidate(event.target.value)}>
                  <option value="">Select a candidate</option>
                  {candidates.map((policy) => (
                    <option key={policy.versionId} value={policy.versionId}>
                      {policy.versionId}
                    </option>
                  ))}
                </select>
              )}
            </Field>
            <button type="submit" className="primary" disabled={!selected || configure.isPending}>
              {current.enabled ? 'Switch candidate' : 'Enable'}
            </button>
            <button
              type="button"
              onClick={() => configure.mutate({ enabled: false, version: null })}
              disabled={!current.enabled || configure.isPending}
            >
              Disable
            </button>
          </form>

          {configure.error && <ErrorNotice error={configure.error} context="Updating shadow configuration" />}
          {configure.isSuccess && (
            <Notice tone="success" title="Configuration updated">
              <span>
                {configure.data.enabled
                  ? `Evaluating ${configure.data.candidateVersion} against new authorizations.`
                  : 'Shadow evaluation is disabled. Comparisons already recorded are kept.'}
              </span>
            </Notice>
          )}
          {current.candidateHash && (
            <span className="field-hint">
              Candidate definition hash <Identifier value={current.candidateHash} />
            </span>
          )}
        </>
      )}
    </Card>
  );
}
