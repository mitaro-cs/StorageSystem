<script lang="ts">
	import { Copy, Sparkles } from '@lucide/svelte';
	import { copy } from '$lib/copy';
	import { passphrase } from './passphrase';

	let {
		password = $bindable(),
		confirm = $bindable(),
		autocomplete = 'new-password'
	}: { password: string; confirm: string; autocomplete?: 'new-password' } = $props();

	const mismatch = $derived(confirm.length > 0 && confirm !== password);
	// «Придумать за меня»: фраза из четырёх слов — показываем её, чтобы человек её сохранил.
	let generated = $state(false);
	function generate() {
		password = confirm = passphrase();
		generated = true;
	}
	const strength = $derived(
		password.length === 0 ? 0 : password.length < 10 ? 1 : password.length < 14 ? 2 : 3
	);
</script>

<div>
	<div class="label-row">
		<label class="label" for="new-password">Пароль</label>
		<button type="button" class="gen" onclick={generate}
			><Sparkles size={14} /> Придумать за меня</button
		>
	</div>
	<input
		id="new-password"
		class="input"
		type={generated ? 'text' : 'password'}
		oninput={() => (generated = false)}
		{autocomplete}
		minlength="10"
		bind:value={password}
		required
	/>
	<div class="meter" aria-hidden="true">
		{#each [1, 2, 3] as i (i)}<span class:on={strength >= i} class="s{strength}"></span>{/each}
	</div>
	{#if generated}
		<p class="saved">
			Запомните или сохраните пароль: <strong>{password}</strong>
			<button type="button" class="gen" onclick={() => copy(password, 'Пароль скопирован')}
				><Copy size={14} /> Копировать</button
			>
		</p>
	{:else}
		<p class="hint">Не короче 10 символов. Удобно взять 3–4 случайных слова.</p>
	{/if}
</div>
<div>
	<label class="label" for="confirm-password">Повторите пароль</label>
	<input
		id="confirm-password"
		class="input"
		type="password"
		{autocomplete}
		bind:value={confirm}
		aria-invalid={mismatch}
		required
	/>
	{#if mismatch}<p class="error-text">Пароли не совпадают</p>{/if}
</div>

<style>
	.label-row {
		display: flex;
		align-items: baseline;
		justify-content: space-between;
		gap: 8px;
	}
	.gen {
		display: inline-flex;
		align-items: center;
		gap: 4px;
		padding: 0;
		border: 0;
		background: none;
		color: var(--text-2);
		font-size: 13px;
		font-weight: 550;
		text-decoration: underline;
		text-underline-offset: 3px;
	}
	.gen:hover {
		color: var(--text);
	}
	.saved {
		margin-top: 8px;
		padding: 10px 12px;
		border-radius: var(--r-s);
		background: var(--amber-soft);
		font-size: 14px;
	}
	.saved strong {
		font-family: var(--mono);
	}
	.meter {
		display: flex;
		gap: 4px;
		margin-top: 8px;
	}
	.meter span {
		flex: 1;
		height: 4px;
		border-radius: 2px;
		background: var(--surface-3);
		transition: background-color var(--dur) var(--ease);
	}
	.meter span.on.s1 {
		background: var(--danger);
	}
	.meter span.on.s2 {
		background: var(--amber);
	}
	.meter span.on.s3 {
		background: var(--ok);
	}
</style>
