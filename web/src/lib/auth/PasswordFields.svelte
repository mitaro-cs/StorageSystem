<script lang="ts">
	let {
		password = $bindable(),
		confirm = $bindable(),
		autocomplete = 'new-password'
	}: { password: string; confirm: string; autocomplete?: 'new-password' } = $props();

	const mismatch = $derived(confirm.length > 0 && confirm !== password);
	const strength = $derived(
		password.length === 0 ? 0 : password.length < 10 ? 1 : password.length < 14 ? 2 : 3
	);
</script>

<div>
	<label class="label" for="new-password">Пароль</label>
	<input
		id="new-password"
		class="input"
		type="password"
		{autocomplete}
		minlength="10"
		bind:value={password}
		required
	/>
	<div class="meter" aria-hidden="true">
		{#each [1, 2, 3] as i (i)}<span class:on={strength >= i} class="s{strength}"></span>{/each}
	</div>
	<p class="hint">Не короче 10 символов. Удобно взять 3–4 случайных слова.</p>
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
