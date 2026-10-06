<script lang="ts">
	import { Check } from '@lucide/svelte';
	import SubjectGlyph from './SubjectGlyph.svelte';

	// Предмет – плитками со значком и цветом, а не выпадающим списком: видно сразу все.
	interface Option {
		id: number;
		name: string;
		color: string;
		icon?: string | null;
	}
	let {
		options,
		value = $bindable(),
		none = '',
		disabled = false,
		label = 'Предмет'
	}: {
		options: Option[];
		value: number | null;
		/** Подпись варианта «без предмета» (пусто – варианта нет). */
		none?: string;
		disabled?: boolean;
		label?: string;
	} = $props();
	const shown = $derived(disabled ? options.filter((o) => o.id === value) : options);
</script>

<div class="sp" role="radiogroup" aria-label={label}>
	{#each shown as o (o.id)}
		<button
			type="button"
			role="radio"
			aria-checked={value === o.id}
			class:on={value === o.id}
			style:--c={o.color}
			{disabled}
			onclick={() => (value = o.id)}
		>
			<SubjectGlyph name={o.name} color={o.color} icon={o.icon ?? null} size={26} />
			<span class="n">{o.name}</span>
			{#if value === o.id}<span class="ok"><Check size={13} strokeWidth={3} /></span>{/if}
		</button>
	{/each}
	{#if none && !disabled}
		<button
			type="button"
			role="radio"
			aria-checked={!value}
			class:on={!value}
			class="none"
			onclick={() => (value = null)}><span class="n">{none}</span></button
		>
	{/if}
</div>

<style>
	.sp {
		display: grid;
		grid-template-columns: repeat(auto-fill, minmax(170px, 1fr));
		gap: 6px;
		max-height: 196px;
		overflow-y: auto;
		padding: 2px;
		margin: -2px;
	}
	button {
		position: relative;
		display: flex;
		align-items: center;
		gap: 8px;
		min-width: 0;
		height: 44px;
		padding: 0 10px 0 8px;
		border: 1px solid var(--border);
		border-radius: 12px;
		background: var(--surface);
		color: var(--text);
		font: inherit;
		font-size: 13.5px;
		font-weight: 550;
		text-align: left;
		transition:
			border-color var(--dur) var(--ease),
			background-color var(--dur) var(--ease),
			transform 120ms var(--ease);
	}
	button:hover:not(:disabled) {
		border-color: var(--border-strong);
	}
	button:active:not(:disabled) {
		transform: scale(0.98);
	}
	button.on {
		--c2: var(--c, var(--accent));
		border-color: var(--c2);
		background: color-mix(in srgb, var(--c2) 10%, var(--surface));
		box-shadow: 0 0 0 1px var(--c2);
	}
	button:disabled {
		opacity: 1;
		cursor: default;
	}
	.none {
		justify-content: center;
		color: var(--text-2);
		border-style: dashed;
	}
	.n {
		flex: 1;
		min-width: 0;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.ok {
		display: grid;
		place-items: center;
		width: 18px;
		height: 18px;
		border-radius: 50%;
		background: var(--c2);
		color: #fff;
	}
	@media (max-width: 520px) {
		.sp {
			grid-template-columns: 1fr 1fr;
		}
	}
</style>
