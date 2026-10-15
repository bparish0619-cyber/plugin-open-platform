import test from 'node:test';
import assert from 'node:assert/strict';
import { createI18n, messages, localizedError } from '../../examples/image-viewer/i18n.js';

test('plugin uses English under Chinese, English and missing system locales', () => {
  for (const locale of ['zh-CN', 'zh-TW', 'en-US', 'ja-JP', undefined]) {
    const local=createI18n({navigator:{language:locale,languages:[locale]}});
    assert.equal(local.language,'en');
    assert.equal(local.t('ui.send'),'Send to glasses');
    assert.equal(local.t('slot.added',{slot:12}),'Slot 12 added. Choose an image.');
  }
  assert.equal(createI18n({}).language,'en');
  assert.deepEqual(Object.keys(messages),['en']);
});

test('English formatting preserves filenames and diagnostic text literally', () => {
  const local=createI18n({navigator:{languages:['zh-CN']}});
  // Non-ASCII filename fixture: preserve user content independently of UI language.
  const raw='<img onerror=x> {slot} $& 中文.png';
  assert.ok(local.t('notice.slotError',{slot:2,error:raw}).includes(raw));
  assert.equal(local.number(105000),'105,000');
});

test('document language and accessible labels are English', () => {
  const attributes={};
  const label={getAttribute:()=> 'ui.send',setAttribute:(k,v)=>{attributes[k]=v;}};
  const root={documentElement:{setAttribute:(k,v)=>{attributes[k]=v;}},
    querySelectorAll:selector=>selector==='[data-i18n-aria]'?[label]:[]};
  createI18n({navigator:{language:'zh-CN'},document:root}).apply();
  assert.deepEqual(attributes,{lang:'en','aria-label':'Send to glasses'});
});

test('existing errors remain English after system locale changes and retain codes', () => {
  const previous=Object.getOwnPropertyDescriptor(globalThis,'navigator');
  try {
    Object.defineProperty(globalThis,'navigator',{configurable:true,value:{languages:['zh-CN']}});
    const error=localizedError('error.ackTimeout');error.code='ACK_TIMEOUT';
    assert.match(error.message,/timed out/);
    globalThis.navigator.languages=['en-US'];
    assert.match(error.message,/timed out/);
    assert.equal(error.code,'ACK_TIMEOUT');
  } finally {
    if(previous) Object.defineProperty(globalThis,'navigator',previous); else delete globalThis.navigator;
  }
});
