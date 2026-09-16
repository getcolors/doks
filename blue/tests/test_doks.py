import json
import os
from pathlib import Path
from unittest.mock import AsyncMock
import pytest
from blue.cli import load_yaml
from package_doks_blue import tools, validate
from package_doks_blue.workflow import start_step, GRAPHS
from package_doks_blue.cli import run

ROOT = Path(__file__).resolve().parents[2]


def fixture(tmp_path, provider='digitalocean'):
    opts = load_yaml((ROOT / f'test/fixtures/{provider}.yml').read_text().replace('WORKDIR', str(tmp_path)))
    opts['blue/event'] = 'build'
    return opts


@pytest.mark.parametrize('provider', ['digitalocean', 'vultr'])
async def test_build_matches_green_golden(tmp_path, provider):
    opts = fixture(tmp_path, provider)
    current = await start_step(opts, {})
    assert current['blue/exit'] == 0
    current = await tools.infrastructure_step(current)
    assert current['blue/exit'] == 0
    current = await tools.registry_step(current)
    assert current['blue/exit'] == 0
    golden = ROOT / 'test/resources/golden' / ('digitalocean-registry' if provider == 'digitalocean' else 'vultr')
    files = sorted(p.relative_to(tmp_path) for p in tmp_path.rglob('*') if p.is_file())
    assert files == sorted(p.relative_to(golden) for p in golden.rglob('*') if p.is_file())
    for path in files:
        assert (tmp_path / path).read_bytes() == (golden / path).read_bytes()


async def test_guards_and_credentials(tmp_path):
    opts = fixture(tmp_path)
    assert (await start_step(opts, {'COLORS_PAR_PROFILE': 'other'}))['blue/exit'] == 2
    for dry in (False, True):
        result = await start_step({**opts, 'blue/event': 'delete', 'blue/dry-run': dry}, {})
        assert result['blue/exit'] == 2
        assert 'protected' in result['blue/err']
    assert (await start_step({**opts, 'blue/event': 'create', 'blue/dry-run': True}, {}))['blue/exit'] == 0
    assert (await start_step({**opts, 'blue/event': 'create'}, {}))['blue/exit'] == 2
    assert (await start_step({**fixture(tmp_path, 'vultr'), 'blue/event': 'registry', 'blue/dry-run': True}, {}))['blue/exit'] == 2
    assert validate.state_errors({**opts, 'profile': '../escape', 'compute-prevent-destroy': 'false'})
    assert validate.registry_name({'profile': 'Hello_World'}) == 'helloworld'


async def test_absent_and_refused_compute(tmp_path, monkeypatch):
    opts = fixture(tmp_path)
    read = AsyncMock(return_value={'status': 'absent'})
    monkeypatch.setattr(tools, 'read_managed_kubernetes', read)
    assert (await tools.load_step({**opts, 'blue/event': 'check'}))['blue/exit'] == 1
    absent = await tools.load_step({**opts, 'blue/event': 'delete'})
    assert absent['doks/cluster-absent'] is True
    converge = AsyncMock(side_effect=AssertionError('must not run'))
    monkeypatch.setattr(tools, 'managed_kubernetes', converge)
    assert (await tools.infrastructure_step(absent))['blue/exit'] == 0
    converge.assert_not_called()
    assert tools.compute_result(opts, {'status': 'error', 'errors': ['ownership refused']})['blue/err'] == 'ownership refused'
    assert read.call_args.args[1]['legacy_state_keys'] == ['doks-fixture/cluster.tfstate']


async def test_registry_integration_and_fallback(tmp_path, monkeypatch):
    opts = {**fixture(tmp_path), 'blue/event': 'create', 'doks/cluster': {'cluster_id': 'cluster-id'}}
    call = AsyncMock(side_effect=[{'status': 204}, {'status': 200, 'body': {'kubernetes_cluster': {'registry_enabled': True}}}])
    monkeypatch.setattr(tools, 'api', call)
    assert (await tools.registry_link_step(opts))['blue/exit'] == 0
    assert call.call_args_list[0].args[3] == {'cluster_uuids': ['cluster-id']}
    call.side_effect = None
    call.return_value = {'status': 404}
    assert (await tools.registry_unlink_step({**opts, 'blue/event': 'delete'}))['blue/exit'] == 0
    call.return_value = {'status': 403, 'body': {'message': 'denied'}}
    assert (await tools.registry_link_step(opts))['blue/exit'] == 1
    call.side_effect = [{'status': 404}, {'status': 200, 'body': {'registry': {'name': 'example'}}}]
    assert await tools.account_registries(opts) == ['example']


async def test_private_credentials_and_cleanup(tmp_path, monkeypatch, capsys):
    opts = fixture(tmp_path)
    secret = '{"auths":{"registry.digitalocean.com":{"auth":"SECRET"}}}'
    monkeypatch.setattr(tools, 'api', AsyncMock(return_value={'status': 200, 'body': json.loads(secret), 'raw': secret}))
    result = await tools.registry_credentials_step(opts)
    assert result['blue/exit'] == 0
    path = Path(result['doks/push-config-path'])
    assert path.stat().st_mode & 0o777 == 0o600
    assert path.parent.stat().st_mode & 0o777 == 0o700
    assert path.read_text() == secret
    assert 'SECRET' not in capsys.readouterr().out
    kubeconfig = Path(tools.kubeconfig_path(opts)); kubeconfig.write_text('credential')
    assert tools.kubeconfig_step(opts)['blue/exit'] == 0
    assert kubeconfig.stat().st_mode & 0o777 == 0o600
    assert tools.cleanup_step({**opts, 'blue/event': 'delete'})['blue/exit'] == 0
    assert not path.exists() and not kubeconfig.exists()
    assert tools.cleanup_step({**opts, 'blue/event': 'delete'})['blue/exit'] == 0


def test_readiness_and_delete_order():
    assert tools.node_report({})['errors'] == ['the cluster reports no nodes']
    node = {'metadata': {'name': 'worker'}, 'status': {'conditions': [{'type': 'Ready', 'status': 'True'}], 'addresses': [{'type': 'ExternalIP', 'address': '192.0.2.1'}]}}
    assert tools.node_report({'items': [node]})['errors'] == []
    node['status']['conditions'][0]['status'] = 'False'
    assert tools.node_report({'items': [node]})['errors'] == ['node worker is not Ready']
    assert GRAPHS['delete'] == ['start', 'load', 'registry-unlink', 'infrastructure', 'registry', 'cleanup']


async def test_cli_and_dry_run_no_writes(tmp_path):
    path = tmp_path / 'colors.yml'
    workdir = tmp_path / 'rendered'
    path.write_text((ROOT / 'test/fixtures/digitalocean.yml').read_text().replace('WORKDIR', str(workdir)))
    for event in ('create', 'check', 'kubeconfig', 'registry'):
        assert (await run(event, '--dry-run', '-f', str(path)))['blue/exit'] == 0
        assert not workdir.exists()
    assert (await run('delete', '--dry-run', '-f', str(path)))['blue/exit'] == 2
    assert (await run('unknown'))['blue/exit'] == 2
    assert (await run('help'))['blue/exit'] == 0


async def test_native_workflow_stops_on_failed_delete_stage(tmp_path, monkeypatch):
    from package_doks_blue.workflow import STEPS, doks_workflow
    from blue.workflow import run as run_workflow
    for failure in ('load', 'registry-unlink', 'infrastructure', 'registry'):
        seen = []
        for name in STEPS:
            async def invoke(opts, name=name):
                seen.append(name)
                return {**opts, 'blue/exit': 1 if name == failure else 0}
            monkeypatch.setitem(STEPS, name, invoke)
        result = await run_workflow(doks_workflow, {**fixture(tmp_path), 'blue/event': 'delete', 'compute-prevent-destroy': False})
        assert result['blue/exit'] == 1
        assert seen == GRAPHS['delete'][:GRAPHS['delete'].index(failure) + 1]
        assert 'cleanup' not in seen


async def test_node_check_process_errors(tmp_path, monkeypatch):
    from blue.runtime import ExecResult
    opts = fixture(tmp_path)
    monkeypatch.setattr(tools.runtime, 'exec', AsyncMock(return_value=ExecResult(1, '', 'connection refused')))
    assert 'connection refused' in (await tools.check_nodes_step(opts))['blue/err']
    monkeypatch.setattr(tools.runtime, 'exec', AsyncMock(return_value=ExecResult(0, 'not-json', '')))
    assert (await tools.check_nodes_step(opts))['blue/exit'] == 1
