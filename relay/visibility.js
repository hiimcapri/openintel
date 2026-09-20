const lower = (value) => String(value ?? '').toLowerCase();
const isAdmin = (user) => !!user && !user.disabled && lower(user.role) === 'admin';
const isTrial = (user) => !!user && lower(user.role) === 'trial';
const isCut = (name, lookup) => lookup(name)?.relayCut === true;

function canReceiveIntel(recipient, reporter, subject, lookup) {
  if (!recipient || recipient.disabled || isTrial(recipient)) return false;
  if (isAdmin(recipient)) return true;
  if (reporter && lower(reporter) !== 'discord') {
    const source = lookup(reporter);
    if (!source || source.disabled || source.quarantined) return false;
  }
  return !isCut(reporter, lookup) && !isCut(subject, lookup);
}

function visiblePositions(reports, recipient, lookup) {
  const latest = new Map();
  for (const report of reports) {
    if (!canReceiveIntel(recipient, report.reporter, report.name, lookup)) continue;
    const key = lower(report.name);
    if (!latest.has(key) || latest.get(key).t <= report.t) latest.set(key, report);
  }
  return [...latest.values()];
}

function visibilityKey(recipient, users) {
  if (isTrial(recipient)) return 'trial';
  if (isAdmin(recipient)) return 'admin';
  return 'public:' + users.filter(user => user.relayCut === true).map(user => lower(user.name)).sort().join(',');
}

module.exports = { isAdmin, isTrial, isCut, canReceiveIntel, visiblePositions, visibilityKey };
