"""Managed compute, the registry state stage, and DigitalOcean HTTP calls."""
import asyncio
import json
import os
from pathlib import Path
import shutil
import tempfile
import urllib.request
import urllib.error
from blue import tofu
from blue.scaffold import content_spec
from blue.runtime import runtime
from colors_compute import backend_plan, registry
from colors_compute.managed import plan_managed_kubernetes, managed_kubernetes, read_managed_kubernetes
from .validate import compute_request, registry_enabled, registry_name

API_BASE = 'https://api.digitalocean.com/v2'
REFUSED = 'managed compute lifecycle refused; inspect state ownership, credentials and the journal'


def profile_dir(opts):
    return Path(opts['workdir']) / opts['profile']


def kubeconfig_path(opts):
    return str(profile_dir(opts) / 'kubeconfig')


def push_config_path(opts):
    return str(profile_dir(opts) / 'registry/push/config.json')


def environment(opts):
    env = dict(os.environ)
    for key, value in opts.items():
        if '/' not in key and value is not None:
            env['COLORS_PAR_' + key.upper().replace('-', '_')] = str(value).lower() if isinstance(value, bool) else str(value)
    env.pop('COLORS_PAR_PROFILE', None)
    return env


def credential_env(opts):
    mapping = dict(registry()['compute'][opts['provider-compute']].get('tofu-env', {}))
    backend = opts['provider-backend']
    if backend in ('r2', 'oci'):
        mapping.update({f'{backend}-access-key-id': 'AWS_ACCESS_KEY_ID', f'{backend}-secret-access-key': 'AWS_SECRET_ACCESS_KEY'})
    return {name: str(opts[key]) for key, name in mapping.items() if opts.get(key)}


def compute_result(opts, result):
    status = result.get('status')
    if status in ('planned', 'ready', 'present'):
        output = {**opts, 'blue/exit': 0, 'doks/cluster': result.get('params')}
        if result.get('kubeconfig_path'):
            output['doks/kubeconfig-path'] = result['kubeconfig_path']
        return output
    if status == 'destroyed':
        return {**opts, 'blue/exit': 0, 'doks/cluster-absent': True}
    return {**opts, 'blue/exit': 1, 'blue/err': '\n'.join(result.get('errors', [])) or REFUSED}


async def infrastructure_step(opts):
    try:
        event = opts['blue/event']
        if event == 'delete' and opts.get('doks/cluster-absent'):
            print('cluster already absent; nothing to destroy')
            return {**opts, 'blue/exit': 0}
        if event == 'build':
            result = plan_managed_kubernetes(opts, compute_request(opts))
            directory = profile_dir(opts) / 'compute/managed-kubernetes'
            directory.mkdir(parents=True, exist_ok=True)
            for name, doc in result['documents'].items():
                (directory / name).write_text(json.dumps(doc, sort_keys=True, indent=2, ensure_ascii=False) + '\n')
        else:
            result = await managed_kubernetes(opts, compute_request(opts), environment(opts))
        outcome = compute_result(opts, result)
        if event == 'delete' and outcome['blue/exit'] == 0:
            print('cluster destroyed')
        return outcome
    except Exception as error:
        return {**opts, 'blue/exit': 1, 'blue/err': f'{REFUSED}: {error}'}


async def load_step(opts):
    try:
        result = await read_managed_kubernetes(opts, compute_request(opts), environment(opts))
        if result.get('status') == 'present':
            return compute_result(opts, result)
        if result.get('status') in ('absent', 'destroyed'):
            if opts['blue/event'] == 'delete':
                return {**opts, 'blue/exit': 0, 'doks/cluster-absent': True}
            return {**opts, 'blue/exit': 1, 'blue/err': 'managed cluster is not present; run create first'}
        return {**opts, 'blue/exit': 1, 'blue/err': 'managed compute inspection refused; existing owned state is required'}
    except Exception as error:
        return {**opts, 'blue/exit': 1, 'blue/err': f'managed compute inspection refused: {error}'}


def registry_document(opts):
    return {
        'terraform': {'required_providers': {'digitalocean': {'source': 'digitalocean/digitalocean', 'version': '2.51.0'}}},
        'provider': {'digitalocean': {}},
        'resource': {'digitalocean_container_registry': {'registry': {'name': registry_name(opts), 'subscription_tier_slug': opts['digitalocean-registry-tier'], 'lifecycle': {'prevent_destroy': opts['compute-prevent-destroy']}}}},
        'output': {'params': {'value': {'kind': 'container-registry', 'provider': 'digitalocean', 'profile': opts['profile'], **{key: '${digitalocean_container_registry.registry.' + key + '}' for key in ('name', 'endpoint', 'server_url')}}}},
    }


async def registry_step(opts):
    if not registry_enabled(opts):
        if opts['blue/event'] == 'delete':
            print('no registry configured; nothing to destroy')
        return {**opts, 'blue/exit': 0}
    directory = str(profile_dir(opts) / 'doks-registry')
    backend = backend_plan(opts, opts['profile'] + '/registry.tfstate')['config']['terraform']['backend']
    kind, config = next(iter(backend.items()))
    opts = tofu.backend_advice(lambda _: directory, kind, config)(opts)
    result = await tofu.tofu_with_spec(opts, [content_spec(directory + '/registry.tf.json', tofu.constructs_json([registry_document(opts)]) + '\n')], dir=directory, env=credential_env(opts))
    if opts['blue/event'] == 'delete' and not result.get('blue/exit'):
        print(f'registry {registry_name(opts)} destroyed')
    if result.get('tofu/outputs', {}).get('params'):
        result['doks/registry'] = result['tofu/outputs']['params']
    return result


async def api(opts, method, path, body=None):
    def request():
        headers = {'Authorization': 'Bearer ' + opts['do-token'], 'Accept': 'application/json'}
        data = None
        if body is not None:
            headers['Content-Type'] = 'application/json'
            data = json.dumps(body).encode()
        req = urllib.request.Request(API_BASE + path, data=data, headers=headers, method=method)
        try:
            response = urllib.request.urlopen(req, timeout=30)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            raw = response.read().decode()
            try:
                parsed = json.loads(raw)
            except ValueError:
                parsed = None
            return {'status': response.status, 'body': parsed, 'raw': raw}
    return await asyncio.to_thread(request)


def ok(response):
    return 200 <= response['status'] <= 299


def api_failure(what, response):
    return f"{what} failed: HTTP {response['status']}" + (' ' + str(response['body']['message']) if isinstance(response.get('body'), dict) and response['body'].get('message') else '')


async def registry_integrated(opts, cluster_id):
    response = await api(opts, 'GET', '/kubernetes/clusters/' + cluster_id)
    return ok(response) and (response.get('body') or {}).get('kubernetes_cluster', {}).get('registry_enabled') is True


async def account_registries(opts):
    many = await api(opts, 'GET', '/registries')
    if ok(many):
        return [r['name'] for r in (many.get('body') or {}).get('registries', [])]
    one = await api(opts, 'GET', '/registry')
    if ok(one):
        return [(one.get('body') or {}).get('registry', {}).get('name')]
    return [] if one['status'] == 404 else None


async def registry_link_step(opts):
    if not registry_enabled(opts) or opts['blue/event'] != 'create':
        return {**opts, 'blue/exit': 0}
    return await integration(opts, False)


async def registry_unlink_step(opts):
    if not registry_enabled(opts) or opts.get('doks/cluster-absent'):
        print('no registry integration to remove')
        return {**opts, 'blue/exit': 0}
    return await integration(opts, True)


async def integration(opts, remove):
    try:
        cluster_id = (opts.get('doks/cluster') or {}).get('cluster_id')
        if not cluster_id:
            raise ValueError('cluster id unavailable from state' if remove else 'cluster id unavailable after converge')
        response = await api(opts, 'DELETE' if remove else 'POST', '/kubernetes/registry', {'cluster_uuids': [cluster_id]})
        if not (ok(response) or remove and response['status'] == 404):
            raise ValueError(api_failure('registry integration removal' if remove else 'registry integration', response))
        if not remove and not await registry_integrated(opts, cluster_id):
            raise ValueError('the cluster does not report the registry integration')
        print(f'registry integration removed from cluster {cluster_id}' if remove else f'registry {registry_name(opts)} integrated with cluster {cluster_id}')
        return {**opts, 'blue/exit': 0}
    except Exception as error:
        return {**opts, 'blue/exit': 1, 'blue/err': str(error)}


def write_private(path, content):
    target = Path(path)
    target.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    target.parent.chmod(0o700)
    fd, temporary = tempfile.mkstemp(dir=target.parent, prefix='.' + target.name + '.')
    try:
        with os.fdopen(fd, 'w') as stream:
            stream.write(content)
        os.replace(temporary, target)
    finally:
        Path(temporary).unlink(missing_ok=True)
    return str(target)


async def registry_credentials_step(opts):
    try:
        response = await api(opts, 'GET', '/registry/docker-credentials?read_write=true&expiry_seconds=3600')
        if not ok(response) or not isinstance((response.get('body') or {}).get('auths'), dict):
            raise ValueError(api_failure('registry credential request', response))
        path = write_private(push_config_path(opts), response['raw'])
        print(f'registry registry.digitalocean.com/{registry_name(opts)}')
        print(f'docker config (read-write, 1 hour): {path}')
        return {**opts, 'blue/exit': 0, 'doks/push-config-path': path}
    except Exception as error:
        return {**opts, 'blue/exit': 1, 'blue/err': str(error)}


def node_report(document):
    nodes = []
    for node in document.get('items', []):
        status = node.get('status', {})
        nodes.append({'name': node.get('metadata', {}).get('name'), 'ip': next((a['address'] for a in status.get('addresses', []) if a['type'] == 'ExternalIP'), None), 'ready?': any(c.get('type') == 'Ready' and c.get('status') == 'True' for c in status.get('conditions', []))})
    errors = [f"node {node['name']} is not Ready" for node in nodes if not node['ready?']]
    return {'nodes': nodes, 'errors': errors if nodes else ['the cluster reports no nodes']}


async def check_nodes_step(opts):
    result = await runtime.exec(['kubectl', '--kubeconfig', opts.get('doks/kubeconfig-path') or kubeconfig_path(opts), 'get', 'nodes', '-o', 'json'])
    if result.exit:
        return {**opts, 'blue/exit': 1, 'blue/err': 'kubectl get nodes failed: ' + (result.err or result.out or '(no output)')}
    try:
        document = json.loads(result.out)
    except ValueError:
        document = {}
    report = node_report(document)
    cluster = opts.get('doks/cluster') or {}
    print(f"cluster {cluster.get('name')} {cluster.get('cluster_id')}")
    for node in report['nodes']:
        print(f"node {node['name']} {node['ip'] or '-'} {'Ready' if node['ready?'] else 'NotReady'}")
    return {**opts, 'blue/exit': 1, 'blue/err': '\n'.join(report['errors'])} if report['errors'] else {**opts, 'blue/exit': 0, 'doks/nodes': report['nodes']}


async def check_registry_step(opts):
    if not registry_enabled(opts):
        return {**opts, 'blue/exit': 0}
    names = await account_registries(opts)
    name = registry_name(opts)
    cluster_id = opts['doks/cluster']['cluster_id']
    error = 'the DigitalOcean registry API gave no answer' if names is None else f'registry {name} does not exist in this account' if name not in names else f'registry {name} is not integrated with cluster {cluster_id}' if not await registry_integrated(opts, cluster_id) else None
    if error:
        return {**opts, 'blue/exit': 1, 'blue/err': error}
    print(f'registry registry.digitalocean.com/{name} integrated with cluster {cluster_id}')
    return {**opts, 'blue/exit': 0}


def kubeconfig_step(opts):
    path = opts.get('doks/kubeconfig-path') or kubeconfig_path(opts)
    if not Path(path).exists():
        return {**opts, 'blue/exit': 1, 'blue/err': f'no kubeconfig was materialized at {path}'}
    Path(path).chmod(0o600)
    print('kubeconfig: ' + path)
    return {**opts, 'blue/exit': 0, 'doks/kubeconfig-path': path}


def cleanup_step(opts):
    try:
        Path(kubeconfig_path(opts)).unlink(missing_ok=True)
        Path(push_config_path(opts)).unlink(missing_ok=True)
        directory = profile_dir(opts) / 'registry'
        shutil.rmtree(directory, ignore_errors=True)
        leftovers = [str(directory), *(str(p) for p in directory.rglob('*'))] if directory.exists() else []
        for path in leftovers:
            print(f'cleanup: could not remove {path} (owned by another user?); remove it by hand')
        print(f'cleanup done: removed {kubeconfig_path(opts)} and {directory}' + (' (with leftovers)' if leftovers else ''))
        print(f"delete complete for {opts['profile']}; the provider removes worker machines and cluster firewalls asynchronously over the next minutes, and check is expected to fail from now on")
        return {**opts, 'blue/exit': 0, 'doks/cleanup-leftovers': leftovers}
    except Exception as error:
        return {**opts, 'blue/exit': 1, 'blue/err': f'cleanup failed after destruction: {error}'}
