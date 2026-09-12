import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { ApiError } from '../api/client';
import { policyApi } from '../api/endpoints';
import { useSession } from '../auth/session';
import { PageHeader } from '../components/Shell';
import {
  Badge,
  Card,
  EmptyState,
  ErrorNotice,
  Field,
  Identifier,
  KeyValues,
  LoadingRows,
  Notice,
  TableScroll,
  Timestamp,
} from '../components/ui';
import type { PolicyRule, PolicyVersion } from '../api/types';

/** A definition that is valid, small, and obviously a candidate rather than a real policy. */
const EXAMPLE_DEFINITION = `{
  "rules": [
    {
      "code": "STRICT_AMOUNT",
      "description": "Candidate declines at or above 1000 minor units.",
      "scoreContribution": 60,
      "flag": "HIGH_AMOUNT",
      "terminal": false,
      "expression": { "operator": "AMOUNT_AT_LEAST", "amountMinor": 1000 }
    }
  ]
}`;

/**
 * Renders a rule expression as readable text.
 *
 * The operator set is closed and typed, so this is a finite translation rather than an interpreter.
 * Nothing from the server is evaluated or injected as markup; every value ends up as text.
 */
function describeExpression(expression: unknown, depth = 0): string {
  if (expression === null || typeof expression !== 'object') return 'unrecognised condition';
  const node = expression as Record<string, unknown>;
  const operator = typeof node.operator === 'string' ? node.operator : '';
  const pad = depth > 0 ? ' ' : '';
  switch (operator) {
    case 'ALL':
    case 'ANY': {
      const children = Array.isArray(node.children) ? node.children : [];
      const joined = children.map((child) => describeExpression(child, depth + 1)).join(operator === 'ALL' ? ' and ' : ' or ');
      return depth > 0 ? `(${joined})` : joined;
    }
    case 'AMOUNT_AT_LEAST':
      return `${pad}amount is at least ${String(node.amountMinor)} minor units`;
    case 'AMOUNT_LESS_THAN':
      return `${pad}amount is below ${String(node.amountMinor)} minor units`;
    case 'CURRENCY_IN':
      return `${pad}currency is one of ${(node.currencies as string[] | undefined)?.join(', ') ?? ''}`;
    case 'COUNTRY_IN':
      return `${pad}country is one of ${(node.countries as string[] | undefined)?.join(', ') ?? ''}`;
    case 'COUNTRY_OUTSIDE':
      return `${pad}country is not one of ${(node.countries as string[] | undefined)?.join(', ') ?? ''}`;
    default:
      return `${pad}unrecognised condition`;
  }
}

/** The definition field is canonical JSON text, not an embedded object, so it has to be parsed. */
function parseRules(definition: string): { rules: PolicyRule[] } | { error: string } {
  try {
    const parsed = JSON.parse(definition) as { rules?: PolicyRule[] };
    return { rules: Array.isArray(parsed.rules) ? parsed.rules : [] };
  } catch {
    return { error: 'The stored definition could not be parsed as JSON.' };
  }
}

export function PoliciesPage() {
  const { can } = useSession();
  const queries = useQueryClient();
  const [selected, setSelected] = useState<string | null>(null);
  const [showRegister, setShowRegister] = useState(false);

  const policies = useQuery({ queryKey: ['policies'], queryFn: ({ signal }) => policyApi.list(signal) });
  const active = policies.data?.find((policy) => policy.versionId === selected) ?? null;

  return (
    <>
      <PageHeader
        title="Policy versions"
        description="Immutable rule sets. A version identifier can never be rebound to different content, so a decision that names a version always means the same rules."
        actions={
          can.registerPolicies ? (
            <button type="button" className="primary" onClick={() => setShowRegister((open) => !open)}>
              {showRegister ? 'Close editor' : 'Register candidate'}
            </button>
          ) : null
        }
      />
      <div className="page-body">
        {can.registerPolicies && showRegister && (
          <RegisterCandidate
            onRegistered={(version) => {
              void queries.invalidateQueries({ queryKey: ['policies'] });
              // The editor stays open so the confirmation remains visible next to what was submitted,
              // and the new version's rules are opened below for review.
              setSelected(version.versionId);
            }}
          />
        )}

        <Card title="Versions" scope="Every stored version, newest first." tight>
          {policies.isPending && <LoadingRows rows={4} label="Loading policy versions" />}
          {policies.error && <div className="card-body"><ErrorNotice error={policies.error} context="Loading policies" /></div>}
          {policies.data && policies.data.length === 0 && <EmptyState title="No policy versions stored" />}
          {policies.data && policies.data.length > 0 && (
            <TableScroll>
              <table>
                <thead>
                  <tr>
                    <th scope="col">Version</th>
                    <th scope="col">Authority</th>
                    <th scope="col" className="numeric">Rules</th>
                    <th scope="col">Definition hash</th>
                    <th scope="col">Created</th>
                    <th scope="col"></th>
                  </tr>
                </thead>
                <tbody>
                  {policies.data.map((policy) => (
                    <tr key={policy.versionId}>
                      <td><Identifier value={policy.versionId} /></td>
                      <td>
                        {policy.origin === 'BUILTIN' ? (
                          <Badge tone="success">Authoritative</Badge>
                        ) : (
                          <Badge tone="neutral">Candidate · not authoritative</Badge>
                        )}
                      </td>
                      <td className="numeric">{policy.ruleCount}</td>
                      <td><span className="id-cell">{policy.definitionHash.slice(0, 16)}…</span></td>
                      <td className="nowrap"><Timestamp value={policy.createdAt} /></td>
                      <td>
                        <button
                          type="button"
                          className="small"
                          onClick={() => setSelected(policy.versionId === selected ? null : policy.versionId)}
                          aria-expanded={policy.versionId === selected}
                        >
                          {policy.versionId === selected ? 'Hide rules' : 'View rules'}
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </TableScroll>
          )}
        </Card>

        {active && <PolicyDetail policy={active} />}
      </div>
    </>
  );
}

function PolicyDetail({ policy }: { policy: PolicyVersion }) {
  const parsed = parseRules(policy.definition);

  return (
    <Card
      title={`Rules in ${policy.versionId}`}
      scope="Evaluated in this order. Every matching rule contributes, and a matching terminal rule stops evaluation."
    >
      <KeyValues
        entries={[
          ['Version', <Identifier value={policy.versionId} />],
          [
            'Authority',
            policy.origin === 'BUILTIN' ? (
              <Badge tone="success">Authoritative policy</Badge>
            ) : (
              <Badge tone="neutral">Candidate · never decides a real payment</Badge>
            ),
          ],
          ['Definition hash', <Identifier value={policy.definitionHash} />],
          ['Registered by', policy.createdBy],
          ['Registered at', <Timestamp value={policy.createdAt} />],
        ]}
      />
      <Notice tone="info" title="About the definition hash">
        The hash covers the canonical form of this policy, which identifies what it means rather than how
        it was written. It is not a hash of the definition text as returned here, because that text comes
        back through JSON storage that normalises key order. Verifying it means re-deriving the canonical
        form, which this page does not attempt, so no verification badge is shown.
      </Notice>

      {'error' in parsed ? (
        <Notice tone="danger" title="Could not read the definition">
          {parsed.error}
        </Notice>
      ) : (
        <TableScroll>
          <table>
            <thead>
              <tr>
                <th scope="col" className="numeric">#</th>
                <th scope="col">Code</th>
                <th scope="col">Condition</th>
                <th scope="col" className="numeric">Contributes</th>
                <th scope="col">Flag</th>
                <th scope="col">Terminal</th>
              </tr>
            </thead>
            <tbody>
              {parsed.rules.map((rule, index) => (
                <tr key={rule.code}>
                  <td className="numeric">{index + 1}</td>
                  <td>
                    <div className="reason-code">{rule.code}</div>
                    <div className="reason-description">{rule.description}</div>
                  </td>
                  <td>{describeExpression(rule.expression)}</td>
                  <td className="numeric">+{rule.scoreContribution}</td>
                  <td><Badge tone="neutral" plain>{rule.flag}</Badge></td>
                  <td>
                    {rule.terminal ? <Badge tone="warning">stops evaluation</Badge> : <span className="id-short">no</span>}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </TableScroll>
      )}
    </Card>
  );
}

/** Registering a candidate. Validation failures come back with the JSON path that was wrong. */
function RegisterCandidate({ onRegistered }: { onRegistered: (version: PolicyVersion) => void }) {
  const [versionId, setVersionId] = useState('');
  const [definitionText, setDefinitionText] = useState(EXAMPLE_DEFINITION);
  const [localError, setLocalError] = useState<string | null>(null);

  const register = useMutation({
    mutationFn: async () => {
      let definition: unknown;
      try {
        definition = JSON.parse(definitionText);
      } catch (cause) {
        throw new ApiError(400, 'INVALID_JSON',
          cause instanceof Error ? `The document is not valid JSON: ${cause.message}` : 'The document is not valid JSON.',
          null);
      }
      return policyApi.register(versionId.trim(), definition);
    },
    onSuccess: onRegistered,
  });

  const idError =
    versionId === '' || /^[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}$/.test(versionId.trim())
      ? null
      : 'Use letters, digits, dots, underscores or hyphens, starting with a letter or digit.';

  return (
    <Card
      title="Register a candidate policy"
      scope="Candidates are never authoritative. Registering one does not change any decision, and there is no way to promote it."
    >
      <form
        className="stack"
        onSubmit={(event) => {
          event.preventDefault();
          setLocalError(null);
          if (idError || !versionId.trim()) {
            setLocalError('Enter a valid version identifier.');
            return;
          }
          register.mutate();
        }}
      >
        <Field
          label="Version identifier"
          hint="Immutable. Reusing one with different content is refused rather than overwriting it."
          error={idError}
        >
          {(props) => (
            <input
              {...props}
              type="text"
              value={versionId}
              onChange={(event) => setVersionId(event.target.value)}
              placeholder="candidate-strict-v1"
              autoCapitalize="none"
              spellCheck={false}
            />
          )}
        </Field>
        <Field
          label="Definition"
          hint="Closed operator set: ALL, ANY, AMOUNT_AT_LEAST, AMOUNT_LESS_THAN, CURRENCY_IN, COUNTRY_IN, COUNTRY_OUTSIDE. No scripts or expressions."
        >
          {(props) => (
            <textarea
              {...props}
              rows={16}
              value={definitionText}
              onChange={(event) => setDefinitionText(event.target.value)}
              spellCheck={false}
            />
          )}
        </Field>
        <div className="row">
          <button type="submit" className="primary" disabled={register.isPending}>
            {register.isPending ? 'Registering…' : 'Register candidate'}
          </button>
          <button type="button" onClick={() => setDefinitionText(EXAMPLE_DEFINITION)}>
            Reset to example
          </button>
        </div>
      </form>

      {localError && <Notice tone="danger" title="Check the form">{localError}</Notice>}
      {register.error && <RegistrationError error={register.error} />}
      {register.data && (
        <Notice tone="success" title={`Stored as ${register.data.versionId}`}>
          <span>
            {register.data.origin === 'CANDIDATE'
              ? 'Registered as a candidate. It is not authoritative and will not decide any real payment.'
              : 'Stored.'}
          </span>
        </Notice>
      )}
    </Card>
  );
}

/**
 * Surfaces the server's structured validation detail.
 *
 * The backend names the offending JSON path, which is the useful part, so it is shown prominently
 * rather than flattened into a generic message.
 */
function RegistrationError({ error }: { error: unknown }) {
  if (!(error instanceof ApiError)) return <ErrorNotice error={error} context="Registration" />;

  if (error.code === 'INVALID_POLICY_DEFINITION' || error.code === 'INVALID_JSON') {
    const [path, ...rest] = error.detail.split(': ');
    const hasPath = error.detail.startsWith('$');
    return (
      <Notice tone="danger" title="The definition was rejected">
        {hasPath && (
          <span>
            At <code>{path}</code>
          </span>
        )}
        <span>{hasPath ? rest.join(': ') : error.detail}</span>
      </Notice>
    );
  }
  if (error.code === 'POLICY_VERSION_CONFLICT') {
    return (
      <Notice tone="warning" title="That version identifier already exists with different content">
        <span>{error.detail}</span>
        <span>Policy versions are immutable. Choose a new identifier.</span>
      </Notice>
    );
  }
  return <ErrorNotice error={error} context="Registration" />;
}
