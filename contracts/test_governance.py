"""Validate governance contracts and the proposed architecture fixture."""
import copy
import json
import unittest
from pathlib import Path

from jsonschema import Draft202012Validator, FormatChecker
from openapi_spec_validator import OpenAPIV31SpecValidator
from referencing import Registry, Resource
from referencing.jsonschema import DRAFT202012

ROOT = Path(__file__).resolve().parent
FILES = ('execution.schema.json', 'governance.schema.json', 'openapi.json')
DOCUMENTS = {name: json.loads((ROOT / name).read_text(encoding='utf-8-sig'))
             for name in FILES}
EXAMPLES = json.loads((ROOT / 'governance-examples.json').read_text(encoding='utf-8-sig'))
TOPOLOGY = json.loads((ROOT / 'agent-topology.example.json').read_text(encoding='utf-8-sig'))
REGISTRY = Registry().with_resources(
    ((ROOT / name).as_uri(), Resource.from_contents(data, default_specification=DRAFT202012))
    for name, data in DOCUMENTS.items()
)


def validator(name):
    return Draft202012Validator(
        {'$ref': (ROOT / 'governance.schema.json').as_uri() + '#/$defs/' + name},
        registry=REGISTRY, format_checker=FormatChecker(),
    )


def references(value):
    if isinstance(value, dict):
        if '$ref' in value:
            yield value['$ref']
        for child in value.values():
            yield from references(child)
    elif isinstance(value, list):
        for child in value:
            yield from references(child)


class GovernanceTests(unittest.TestCase):
    def test_governance_examples_and_required_fields(self):
        schema = DOCUMENTS['governance.schema.json']
        Draft202012Validator.check_schema(schema)
        for name, example in EXAMPLES.items():
            validator(name).validate(example)
            for field in schema['$defs'][name]['required']:
                with self.subTest(name=name, missing=field):
                    invalid = copy.deepcopy(example)
                    del invalid[field]
                    self.assertFalse(validator(name).is_valid(invalid))

    def test_dedicated_roles_are_exclusive(self):
        for role in ('LIBRARY', 'BUGFIX'):
            for schema_name in ('AgentNode', 'AgentRegistration'):
                allowed_fields = DOCUMENTS['governance.schema.json']['$defs'][schema_name]['required']
                node = {key: value for key, value in EXAMPLES['AgentNode'].items()
                        if key in allowed_fields}
                node['roles'] = [role]
                validator(schema_name).validate(node)
                for extra in ('ROOT', 'MANAGER', 'WORKER', 'LIBRARY', 'BUGFIX'):
                    with self.subTest(role=role, extra=extra, schema=schema_name):
                        node['roles'] = [role, extra]
                        self.assertFalse(validator(schema_name).is_valid(node))
                node['roles'] = [role]
                node['parentId'] = None
                self.assertFalse(validator(schema_name).is_valid(node))

    def test_topology_fixture_matches_user_architecture(self):
        nodes = {node['agentId']: node for node in TOPOLOGY['nodes']}
        self.assertEqual(len(nodes), len(TOPOLOGY['nodes']))
        for node in nodes.values():
            validator('AgentNode').validate(node)
        roots = [n for n in nodes.values() if 'ROOT' in n['roles']]
        self.assertEqual(len(roots), 1)
        root = roots[0]
        library = [n for n in nodes.values() if n['roles'] == ['LIBRARY']]
        bugfix = [n for n in nodes.values() if n['roles'] == ['BUGFIX']]
        self.assertEqual(len(library), 1)
        self.assertEqual(len(bugfix), 1)
        self.assertEqual(library[0]['parentId'], root['agentId'])
        self.assertEqual(bugfix[0]['parentId'], root['agentId'])
        bug_id = bugfix[0]['agentId']
        self.assertFalse(any(n['parentId'] == bug_id for n in nodes.values()))
        peers = set()
        for left, right in TOPOLOGY['collaborationLinks']:
            self.assertIn(left, nodes)
            self.assertIn(right, nodes)
            if left == bug_id:
                peers.add(right)
            if right == bug_id:
                peers.add(left)
        self.assertEqual(peers, {root['agentId'], library[0]['agentId']})
        self.assertEqual(nodes['manager-business']['parentId'], root['agentId'])
        assignments = {item['agentId']: item for item in TOPOLOGY['workAssignments']}
        for agent_id, assignment in assignments.items():
            node = nodes[agent_id]
            if node['roles'] == ['MANAGER']:
                self.assertEqual(node['roles'], ['MANAGER'])
                self.assertEqual(node['parentId'], 'manager-business')
            else:
                self.assertEqual(node['roles'], ['WORKER'])
                module = assignments[node['parentId']]
                self.assertEqual(nodes[node['parentId']]['roles'], ['MANAGER'])
                if assignment['kind'] == 'PACKAGE':
                    self.assertEqual(module['kind'], 'MODULE')
                    self.assertEqual(assignment['modulePath'], module['modulePath'])
                    self.assertTrue(all(path.startswith(module['modulePath'] + '/')
                                        for path in assignment['ownedPaths']))
                else:
                    self.assertTrue(assignment['scopeRef'].startswith(module['scopeRef'] + '/'))
        self.assertTrue({'MODULE', 'DATABASE', 'SERVICE_GOVERNANCE'}.issubset(
            {a['kind'] for a in assignments.values()}))

    def test_approval_and_event_negative_shapes(self):
        approval = copy.deepcopy(EXAMPLES['Approval'])
        approval['scope']['candidateCommit'] = None
        self.assertFalse(validator('Approval').is_valid(approval))
        approval = copy.deepcopy(EXAMPLES['Approval'])
        approval['status'] = 'APPROVED'
        self.assertFalse(validator('Approval').is_valid(approval))
        event = copy.deepcopy(EXAMPLES['EventEnvelope'])
        event['payload'] = EXAMPLES['AgentNode']
        self.assertFalse(validator('EventEnvelope').is_valid(event))
        request = {'decision': 'APPROVE', 'reason': 'Reviewed'}
        validator('ApprovalDecision').validate(request)
        request['decidedBy'] = 'forged-root'
        self.assertFalse(validator('ApprovalDecision').is_valid(request))

    def test_all_references_resolve_offline(self):
        for name, document in DOCUMENTS.items():
            resolver = REGISTRY.resolver(base_uri=(ROOT / name).as_uri())
            for reference in references(document):
                with self.subTest(file=name, reference=reference):
                    resolver.lookup(reference)

    def test_openapi_spec_and_write_guards(self):
        spec = DOCUMENTS['openapi.json']
        OpenAPIV31SpecValidator(spec, base_uri=(ROOT / 'openapi.json').as_uri()).validate()
        self.assertEqual(spec['security'], [{'bearerAuth': []}])
        identifiers = []
        for path in spec['paths'].values():
            for method in ('get', 'post', 'put'):
                if method not in path:
                    continue
                operation = path[method]
                identifiers.append(operation['operationId'])
                self.assertNotIn('security', operation)
                if method != 'get':
                    self.assertIn({'$ref': '#/components/parameters/IdempotencyKey'},
                                  operation['parameters'])
        self.assertEqual(len(identifiers), 46)
        implemented = {op['operationId'] for path in spec['paths'].values()
                       for method, op in path.items()
                       if method in ('get', 'post', 'put') and op.get('x-implemented')}
        self.assertEqual(implemented, {'createProject', 'getProject', 'searchLibraryTasks',
                                      'createTask', 'getTask', 'registerAgent', 'getAgent', 'changeParent',
                                      'publishTask', 'claimTask', 'startAttempt', 'heartbeat', 'submitResult',
                                      'cancelTask', 'reportStopped', 'issueWorkerCredential',
                                      'revokeWorkerCredential', 'verifyTaskResult', 'readAuditEvents',
                                      'bindLibraryRepository', 'verifyCandidateCi', 'getCandidateCi',
                                      'getAcceptedIntegrationResult', 'registerIntegrationCandidate', 'getIntegrationCandidate',
                                      'getLibraryRepository', 'getIntegrationRelease', 'approveIntegrationRelease',
                                      'beginIntegrationRelease', 'confirmIntegrationRelease',
                                      'claimBugfixTask', 'getLibraryFailureEvidence', 'publishTaskBatch',
                                      'getProjectDispatch', 'setProjectDispatch', 'readAttemptInstructions',
                                      'getGovernance'})
        self.assertEqual(len(identifiers), len(set(identifiers)))


if __name__ == '__main__':
    unittest.main(verbosity=2)
