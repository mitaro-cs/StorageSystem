<script lang="ts">
	import { DIFFICULTY } from './difficulty';

	let { value = $bindable(null) }: { value: number | null } = $props();
</script>

<fieldset class="dp">
	<legend class="label">Сложность <span class="faint">(необязательно)</span></legend>
	<div class="seg" role="radiogroup" aria-label="Сложность">
		<button
			type="button"
			role="radio"
			aria-checked={value === null}
			class:on={value === null}
			onclick={() => (value = null)}>Не указана</button
		>
		{#each DIFFICULTY as d (d.value)}
			<button
				type="button"
				role="radio"
				aria-checked={value === d.value}
				class={d.tone}
				class:on={value === d.value}
				onclick={() => (value = d.value)}
			>
				<span class="bars" aria-hidden="true">
					{#each [1, 2, 3] as b (b)}<span class="bar" class:lit={b <= d.value}></span>{/each}
				</span>
				{d.label}
			</button>
		{/each}
	</div>
</fieldset>

<style>
	.dp {
		border: 0;
		margin: 0;
		padding: 0;
	}
	.seg {
		display: grid;
		grid-template-columns: repeat(4, 1fr);
		gap: 4px;
		padding: 4px;
		border-radius: 14px;
		background: var(--surface-2);
	}
	@media (max-width: 420px) {
		.seg {
			grid-template-columns: 1fr 1fr;
		}
	}
	button {
		display: inline-flex;
		align-items: center;
		justify-content: center;
		gap: 7px;
		height: 38px;
		border: 0;
		border-radius: 10px;
		background: transparent;
		color: var(--text-2);
		font: inherit;
		font-size: 14px;
		font-weight: 550;
		transition:
			background-color var(--dur) var(--ease),
			color var(--dur) var(--ease),
			transform 120ms var(--ease);
	}
	button:active {
		transform: scale(0.97);
	}
	button.on {
		background: var(--surface);
		color: var(--text);
		box-shadow: 0 1px 3px rgb(16 18 24 / 0.1);
	}
	.ok {
		--tone: var(--ok);
	}
	.amber {
		--tone: var(--amber);
	}
	.danger {
		--tone: var(--danger);
	}
	button.on:not(:first-child) {
		color: var(--tone);
	}
	.bars {
		display: inline-flex;
		align-items: flex-end;
		gap: 2px;
		height: 12px;
	}
	.bar {
		width: 3px;
		border-radius: 2px;
		background: color-mix(in srgb, var(--tone) 30%, transparent);
	}
	.bar:nth-child(1) {
		height: 6px;
	}
	.bar:nth-child(2) {
		height: 9px;
	}
	.bar:nth-child(3) {
		height: 12px;
	}
	.bar.lit {
		background: var(--tone);
	}
</style>
