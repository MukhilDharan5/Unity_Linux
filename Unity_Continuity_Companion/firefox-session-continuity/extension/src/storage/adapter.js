/** Asynchronous logical persistence contract. No runtime tab/window IDs here. */
export class StorageAdapter {
  async initialize() { throw new Error('Not implemented'); }
  async save(_snapshot,_options={}) { throw new Error('Not implemented'); }
  async load(_id) { throw new Error('Not implemented'); }
  async loadLatestActive() { throw new Error('Not implemented'); }
  async list() { throw new Error('Not implemented'); }
  async delete(_id) { throw new Error('Not implemented'); }
  async clear() { throw new Error('Not implemented'); }
  async selectActive(_id) { throw new Error('Not implemented'); }
  async close(_id) { throw new Error('Not implemented'); }
  async settings() { throw new Error('Not implemented'); }
  async changePolicy(_settings,_operation={}) { throw new Error('Not implemented'); }
}
