"""DOKS verb graphs. Dry runs skip every step after validation."""
from pathlib import Path
from blue import dry_run, progress
from blue.cli import read_pars
from blue.lifecycle import preflight
from blue.workflow import workflow, failed
from . import tools, validate

EVENTS = ['build', 'create', 'check', 'kubeconfig', 'registry', 'delete']
DEFAULTS = {'provider-compute': 'digitalocean', 'provider-backend': 'r2', 'compute-prevent-destroy': True, 'workdir': '.colors'}


async def start_step(opts, env=None):
    def after(current, _env, _context):
        directory = Path(str(current['workdir']))
        if not directory.is_absolute() and current.get('blue/state-file'):
            directory = Path(current['blue/state-file']).resolve().parent / directory
        return {**current, 'workdir': str(directory), 'blue/exit': 0}
    return await preflight(opts, defaults=DEFAULTS, overlay=read_pars, env=env, validators=[
        lambda o, e, c: validate.env_errors(e),
        lambda o, e, c: validate.state_errors(o),
        lambda o, e, c: [':digitalocean-registry-tier is not set; the registry verb needs a deployment-owned registry'] if c['event'] == 'registry' and not validate.registry_enabled(o) else [],
        lambda o, e, c: validate.secret_errors(o, c['event']) if c['real'] else [],
        lambda o, e, c: ['compute destruction is protected; set COLORS_PAR_COMPUTE_PREVENT_DESTROY=false to delete'] if c['event'] == 'delete' and o.get('compute-prevent-destroy') is not False else [],
    ], after_validate=after)


STEPS = {'start': start_step, 'infrastructure': tools.infrastructure_step, 'load': tools.load_step, 'registry': tools.registry_step, 'registry-link': tools.registry_link_step, 'registry-unlink': tools.registry_unlink_step, 'check-nodes': tools.check_nodes_step, 'check-registry': tools.check_registry_step, 'kubeconfig': tools.kubeconfig_step, 'registry-credentials': tools.registry_credentials_step, 'cleanup': tools.cleanup_step}
GRAPHS = {
    'build': ['start', 'infrastructure', 'registry', 'registry-link'],
    'create': ['start', 'infrastructure', 'registry', 'registry-link'],
    'delete': ['start', 'load', 'registry-unlink', 'infrastructure', 'registry', 'cleanup'],
    'check': ['start', 'load', 'check-nodes', 'check-registry'],
    'kubeconfig': ['start', 'load', 'kubeconfig'],
    'registry': ['start', 'registry-credentials'],
}


def wire_fn(step, opts):
    graph = GRAPHS[opts['blue/event']]
    name = step.removeprefix('doks/')
    if name not in graph:
        return None
    index = graph.index(name)
    return [STEPS[name], *(['doks/' + graph[index + 1]] if index + 1 < len(graph) else [])]


def next_steps(_step, successors, opts):
    return [] if failed(opts) else [(step, opts) for step in (successors or [])]


doks_workflow = dry_run.advise(progress.advise(workflow(start='doks/start', wire_fn=wire_fn, next_fn=next_steps)), ['doks/' + name for name in STEPS if name != 'start'])
