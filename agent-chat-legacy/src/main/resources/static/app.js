(() => {
  const form = document.querySelector('#zine-form');
  const input = document.querySelector('#image-input');
  const stage = document.querySelector('#paper-stage');
  const emptyState = document.querySelector('#empty-state');
  const previewWrap = document.querySelector('#preview-wrap');
  const sourcePreview = document.querySelector('#source-preview');
  const resultWrap = document.querySelector('#result-wrap');
  const resultPreview = document.querySelector('#result-preview');
  const downloadLink = document.querySelector('#download-link');
  const replaceButton = document.querySelector('#replace-button');
  const generateButton = document.querySelector('#generate-button');
  const status = document.querySelector('#status');
  const modeLabel = document.querySelector('#mode-label');
  const modes = [...document.querySelectorAll('[data-mode]')];
  const apiKeyInput = document.querySelector('#qwen-api-key');
  const apiKeyStatus = document.querySelector('#api-key-status');
  const saveApiKeyButton = document.querySelector('#save-api-key');
  const clearApiKeyButton = document.querySelector('#clear-api-key');

  let selectedFile = null;
  let selectedMode = 'gathered';
  let objectUrl = null;

  const readJson = async (response) => response.json().catch(() => ({}));

  const loadApiKeyStatus = async () => {
    try {
      const response = await fetch('/api/settings/keys', { credentials: 'same-origin' });
      const payload = await readJson(response);
      if (!response.ok) throw new Error(payload.message || '无法读取 API Key 状态');
      const configured = Boolean(payload.configured);
      apiKeyStatus.textContent = configured ? `已配置（${payload.masked}）` : '尚未配置。Key 会加密保存在当前用户下。';
      clearApiKeyButton.hidden = !configured;
      return configured;
    } catch (error) {
      apiKeyStatus.textContent = error.message || '无法读取 API Key 状态';
      return false;
    }
  };

  const saveApiKey = async () => {
    const qwenApiKey = apiKeyInput.value.trim();
    if (!qwenApiKey) {
      apiKeyStatus.textContent = '请先输入 API Key。';
      return false;
    }
    saveApiKeyButton.disabled = true;
    try {
      const response = await fetch('/api/settings/keys', {
        method: 'PUT',
        credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ qwenApiKey })
      });
      const payload = await readJson(response);
      if (!response.ok) throw new Error(payload.message || 'API Key 保存失败');
      apiKeyInput.value = '';
      apiKeyStatus.textContent = '已保存。生成请求会使用你的 API Key。';
      clearApiKeyButton.hidden = false;
      return true;
    } catch (error) {
      apiKeyStatus.textContent = error.message || 'API Key 保存失败';
      return false;
    } finally {
      saveApiKeyButton.disabled = false;
    }
  };

  const clearApiKey = async () => {
    clearApiKeyButton.disabled = true;
    try {
      const response = await fetch('/api/settings/keys', {
        method: 'DELETE',
        credentials: 'same-origin'
      });
      if (!response.ok) {
        const payload = await readJson(response);
        throw new Error(payload.message || 'API Key 清除失败');
      }
      apiKeyStatus.textContent = '已清除。';
      clearApiKeyButton.hidden = true;
    } catch (error) {
      apiKeyStatus.textContent = error.message || 'API Key 清除失败';
    } finally {
      clearApiKeyButton.disabled = false;
    }
  };

  const setStatus = (message, kind = '') => {
    status.textContent = message;
    status.className = `status${kind ? ` is-${kind}` : ''}`;
  };

  const setFile = (file) => {
    if (!file) return;
    if (!file.type.startsWith('image/')) {
      setStatus('请选择图片文件（JPG、PNG、WEBP 等）。', 'error');
      return;
    }
    if (file.size > 10 * 1024 * 1024) {
      setStatus('图片超过 10 MB，请换一张更轻的图片。', 'error');
      return;
    }
    selectedFile = file;
    if (objectUrl) URL.revokeObjectURL(objectUrl);
    objectUrl = URL.createObjectURL(file);
    sourcePreview.src = objectUrl;
    sourcePreview.alt = `待编辑的图片：${file.name}`;
    emptyState.hidden = true;
    previewWrap.hidden = false;
    resultWrap.hidden = true;
    generateButton.disabled = false;
    setStatus('图片已放入。可以开始编排。', 'success');
  };

  input.addEventListener('change', () => setFile(input.files[0]));
  replaceButton.addEventListener('click', () => input.click());

  ['dragenter', 'dragover'].forEach((eventName) => stage.addEventListener(eventName, (event) => {
    event.preventDefault();
    stage.classList.add('is-dragging');
  }));
  ['dragleave', 'drop'].forEach((eventName) => stage.addEventListener(eventName, (event) => {
    event.preventDefault();
    stage.classList.remove('is-dragging');
  }));
  stage.addEventListener('drop', (event) => setFile(event.dataTransfer.files[0]));

  modes.forEach((button) => button.addEventListener('click', () => {
    selectedMode = button.dataset.mode;
    modes.forEach((item) => {
      const selected = item === button;
      item.classList.toggle('is-selected', selected);
      item.setAttribute('aria-pressed', String(selected));
    });
    modeLabel.textContent = selectedMode === 'gathered' ? '实景拼贴' : '影像蒸馏';
  }));

  saveApiKeyButton.addEventListener('click', saveApiKey);
  clearApiKeyButton.addEventListener('click', clearApiKey);
  loadApiKeyStatus();

  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    if (!selectedFile) {
      setStatus('先放入一张照片。', 'error');
      return;
    }

    const body = new FormData();
    body.append('image', selectedFile);
    body.append('mode', selectedMode);
    body.append('language', document.querySelector('#language').value);
    body.append('text', document.querySelector('#text').value);
    body.append('guidance', document.querySelector('#guidance').value);

    generateButton.disabled = true;
    generateButton.classList.add('is-loading');
    setStatus('正在阅读现场，整理成一页纸刊……');
    try {
      const response = await fetch('/api/zine/generate', { method: 'POST', body });
      const payload = await response.json().catch(() => ({}));
      if (!response.ok) throw new Error(payload.message || `生成失败（${response.status}）`);
      resultPreview.src = payload.imageUrl;
      resultPreview.alt = `${payload.mode === 'gathered' ? '实景拼贴' : '影像蒸馏'}生成结果`;
      downloadLink.href = payload.imageUrl;
      resultWrap.hidden = false;
      previewWrap.hidden = true;
      setStatus(`${payload.rationale || '成品已生成。'} 可以打开成品查看。`, 'success');
    } catch (error) {
      setStatus(error.message || '生成失败，请稍后重试。', 'error');
    } finally {
      generateButton.disabled = false;
      generateButton.classList.remove('is-loading');
    }
  });
})();
