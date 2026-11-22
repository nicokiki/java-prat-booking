const {test} = require('node:test');
const assert = require('node:assert/strict');
const {readFileSync} = require('node:fs');
const {resolve} = require('node:path');
const vm = require('node:vm');

const install = readFileSync(resolve(__dirname, '../../../target/availability-observer-fixture.js'), 'utf8');
const read = readFileSync(resolve(__dirname, '../../../target/availability-read-fixture.js'), 'utf8');
const render = readFileSync(resolve(__dirname, '../../../target/tee-time-render-fixture.js'), 'utf8');

function observer() {
  const handlers = {};
  const values = {Recorrido: '02', Hoyos: '1', Plazas: '2', HidFechaJuego: '20261122'};
  const context = vm.createContext({
    window: {jQuery: () => ({on: (name, handler) => { handlers[name] = handler; }})},
    document: {getElementById: id => ({value: values[id]})},
  });
  const run = source => vm.runInContext('(function(){' + source + '})()', context);
  assert.equal(run(install), true);
  return {
    values, handlers,
    read: () => run(read),
    install: () => run(install),
    send: xhr => handlers['ajaxSend.pratAvailability']({}, xhr, {
      url: 'https://example.invalid/api/Front/ReservasSocios/ObtenerHorasDisponiblesReservasSocios',
      data: JSON.stringify({idRecorrido: values.Recorrido, numHoyos: values.Hoyos,
        numJugadores: values.Plazas, fechaJuego: values.HidFechaJuego.replace(/^(\d{4})(\d{2})(\d{2})$/, '$1/$2/$3'),
        idSocio: 'private-member', token: 'private-token'}),
    }),
    complete: xhr => handlers['ajaxComplete.pratAvailability']({}, xhr),
  };
}

function response(times = [], status = 200, code = 1, open = true) {
  return {status, responseJSON: {res: {cod: code, msg: 'private-message'},
    datos: {lista: times, sistemaAbierto: open}}};
}

test('pending and stale responses cannot replace the newest availability request', () => {
  const observed = observer();
  const old = response();
  const latest = response([{hora: '13:50'}]);
  observed.send(old);
  observed.send(latest);
  observed.complete(old);
  assert.equal(observed.read().pending, true);
  observed.complete(latest);
  assert.equal(observed.read().pending, false);
  assert.equal(observed.read().timeCount, 1);
});

test('response evidence matches course, holes, players and play date', () => {
  const observed = observer();
  const xhr = response();
  observed.send(xhr);
  observed.complete(xhr);
  for (const [field, changed] of Object.entries({Recorrido: '03', Hoyos: '2', Plazas: '3', HidFechaJuego: '20261123'})) {
    const original = observed.values[field];
    observed.values[field] = changed;
    assert.equal(observed.read(), null);
    observed.values[field] = original;
  }
  assert.equal(observed.read().timeCount, 0);
});

test('only safe scalar response evidence is retained', () => {
  const observed = observer();
  const xhr = response([{hora: '13:50', nombre: 'private-name', licencia: 'private-licence'}]);
  observed.send(xhr);
  observed.complete(xhr);
  const evidence = observed.read();
  assert.equal(JSON.stringify(evidence).includes('private'), false);
  assert.deepEqual(Object.keys(evidence).sort(), ['apiCode', 'httpStatus', 'key', 'pending', 'requestId', 'systemOpen', 'timeCount']);
});

test('HTTP failure, API failure, closed system and malformed response remain distinguishable', () => {
  const observed = observer();
  const failed = response([], 503, -1, false);
  observed.send(failed);
  observed.complete(failed);
  assert.equal(observed.read().httpStatus, 503);
  assert.equal(observed.read().apiCode, -1);
  assert.equal(observed.read().systemOpen, false);
  const malformed = {status: 200};
  observed.send(malformed);
  observed.complete(malformed);
  assert.equal(observed.read().apiCode, null);
  assert.equal(observed.read().timeCount, null);
  assert.equal(observed.read().systemOpen, null);
});

test('installation is idempotent and does not require or issue network requests', () => {
  const observed = observer();
  const send = observed.handlers['ajaxSend.pratAvailability'];
  assert.equal(observed.install(), true);
  assert.equal(observed.handlers['ajaxSend.pratAvailability'], send);
  assert.equal(vm.runInNewContext('(function(){' + install + '})()', {window: {}}), false);
});

function renderedOptions(options) {
  const text = [];
  const canvas = {getContext: () => ({scale(){},fillRect(){},strokeRect(){},fillText: value => text.push(value)}),toDataURL: () => 'data:image/png;base64,fixture'};
  const context = {
    inputs:['<select>saved snapshot</select>',{course:'TEE 1 ROSA',date:'20261124',players:2,capturedAt:'2026-11-22T20:00:06+01:00'}],
    document:{createElement: name => {
      if (name === 'template') return {content:{querySelector: () => ({options,disabled:false})}};
      assert.equal(name,'canvas');
      return canvas;
    }}
  };
  const result = vm.runInNewContext('(function(){'+render+'}).apply(null,inputs)',context);
  return {result,text,canvas};
}

test('the production PNG renderer keeps all rows, including more than 100 options', () => {
  const options = Array.from({length:150},() => ({label:'08:00 (4)',selected:false,disabled:false}));
  options.push({label:'17:30 (2)',selected:false,disabled:true});
  const rendered = renderedOptions(options);
  assert.equal(rendered.result.optionCount,151);
  assert.equal(rendered.canvas.height,(96+151*24)*2);
  assert.equal(rendered.text.length,153);
  assert.equal(rendered.text.at(-1),'17:30 (2)');
});

test('public PNG rows keep time/capacity but remove member labels and unknown text', () => {
  const rendered = renderedOptions([
    {label:'08:00 (4) private-member private-licence',selected:true,disabled:false},
    {label:'private-member',selected:false,disabled:false},
    {label:'',selected:false,disabled:false}
  ]);
  assert.deepEqual(rendered.text.slice(2),['08:00 (4)','[redacted]','']);
  assert.equal(JSON.stringify(rendered.text).includes('private'),false);
  assert.equal(rendered.result.optionCount,3);
});

test('oversized dropdowns fail safely rather than publish a partial PNG', () => {
  const rendered = renderedOptions(Array.from({length:501},() => ({label:'08:00'})));
  assert.equal(rendered.result,null);
  assert.equal(rendered.text.length,0);
});