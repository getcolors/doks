"""Desired-state and credential validation before workflow effects."""
import re
from colors_compute import registry
from colors_compute.managed import managed_errors, _recipes


def missing(value):
    return value is None or isinstance(value, str) and not value.strip()


def registry_enabled(opts):
    return not missing(opts.get('digitalocean-registry-tier'))


def registry_name(opts):
    return re.sub('[^a-z0-9-]', '', str(opts.get('profile', '')).lower())


def compute_request(opts):
    return {'legacy_state_keys': [f"{opts['profile']}/cluster.tfstate"]}


def env_errors(env):
    return ['COLORS_PAR_PROFILE is set; profile must come from colors.yml only'] if env.get('COLORS_PAR_PROFILE') else []


def state_errors(opts):
    errors = [f':{k} is required' for k in ('profile', 'workdir', 'provider-compute', 'provider-backend', 'compute-prevent-destroy') if missing(opts.get(k))]
    if not missing(opts.get('profile')) and not re.fullmatch('[A-Za-z0-9][A-Za-z0-9_-]{0,62}', str(opts['profile'])):
        errors.append(':profile must be a safe identifier')
    providers = sorted(_recipes())
    if opts.get('provider-compute') not in providers:
        errors.append(':provider-compute must be one of ' + ', '.join(providers))
    if not isinstance(opts.get('compute-prevent-destroy'), bool):
        errors.append(':compute-prevent-destroy must be true or false')
    if not errors:
        errors.extend(managed_errors(opts, compute_request(opts)))
    if registry_enabled(opts):
        if opts.get('provider-compute') != 'digitalocean':
            errors.append(':digitalocean-registry-tier requires :provider-compute digitalocean')
        if opts.get('digitalocean-registry-tier') not in ('starter', 'basic', 'professional'):
            errors.append(':digitalocean-registry-tier must be starter, basic, or professional')
        if not re.fullmatch('[a-z0-9][a-z0-9-]{1,62}', registry_name(opts)):
            errors.append('the profile-derived registry name is not a valid DigitalOcean registry name')
    return errors


def secret_errors(opts, event):
    provider = registry()['compute'].get(opts.get('provider-compute'), {}).get('secrets', [])
    backend = registry()['backend'].get(opts.get('provider-backend'), {}).get('secrets', [])
    required = provider + backend if event in ('create', 'delete') else backend + (['do-token'] if registry_enabled(opts) else []) if event == 'check' else backend if event == 'kubeconfig' else ['do-token'] if event == 'registry' else []
    return ['required credential is not set: COLORS_PAR_' + key.upper().replace('-', '_') for key in dict.fromkeys(required) if missing(opts.get(key))]
