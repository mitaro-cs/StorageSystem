<script module lang="ts">
	export interface Option<V> {
		value: V;
		label: string;
		hint?: string;
	}
</script>

<script lang="ts" generics="T extends string | number">
	import { Check, ChevronRight } from '@lucide/svelte';
	import FieldPopup from './FieldPopup.svelte';

	// Выбор из списка в стиле сайта (0.9.8): системный <select> на телефонах и в окне хоста выглядел
	// по-разному и выбивался из оформления. Клавиатура – как у списка: стрелки, Enter, Esc, буквы.

	let {
		value = $bindable(),
		options,
		id,
		label,
		disabled = false,
		compact = false,
		onchange
	}: {
		value: T;
		options: Option<T>[];
		id?: string;
		/** Подпись для экранного диктора, если нет <label for>. */
		label?: string;
		disabled?: boolean;
		/** Ниже и уже – для фильтров в строке. */
		compact?: boolean;
		onchange?: (v: T) => void;
	} = $props();

	let open = $state(false);
	let button: HTMLButtonElement | undefined = $state();
	let active = $state(0);
	const current = $derived(options.find((o) => o.value === value));
	const listId = `sel-${Math.random().toString(36).slice(2, 8)}`;

	function show() {
		if (disabled) return;
		active = Math.max(
			0,
			options.findIndex((o) => o.value === value)
		);
		open = true;
	}

	function choose(o: Option<T>) {
		open = false;
		button?.focus();
		if (o.value === value) return;
		value = o.value;
		onchange?.(o.value);
	}

	function key(e: KeyboardEvent) {
		if (disabled) return;
		if (!open) {
			if (['ArrowDown', 'ArrowUp', 'Enter', ' '].includes(e.key)) {
				e.preventDefault();
				show();
			}
			return;
		}
		if (e.key === 'ArrowDown') active = Math.min(options.length - 1, active + 1);
		else if (e.key === 'ArrowUp') active = Math.max(0, active - 1);
		else if (e.key === 'Home') active = 0;
		else if (e.key === 'End') active = options.length - 1;
		else if (e.key === 'Enter' || e.key === ' ') choose(options[active]);
		else if (e.key === 'Tab') open = false;
		else if (e.key.length === 1) {
			const k = e.key.toLowerCase();
			const i = options.findIndex((o, n) => n > active && o.label.toLowerCase().startsWith(k));
			const j = i >= 0 ? i : options.findIndex((o) => o.label.toLowerCase().startsWith(k));
			if (j >= 0) active = j;
			return;
		} else return;
		e.preventDefault();
	}
</script>

<button
	{id}
	type="button"
	class="select pick"
	class:compact
	class:open
	bind:this={button}
	{disabled}
	role="combobox"
	aria-label={label}
	aria-haspopup="listbox"
	aria-expanded={open}
	aria-controls={listId}
	aria-activedescendant={open ? `${listId}-${active}` : undefined}
	onclick={() => (open ? (open = false) : show())}
	onkeydown={key}
>
	<span class="value">{current?.label ?? ''}</span>
	<span class="chev" aria-hidden="true"><ChevronRight size={16} /></span>
</button>

<FieldPopup
	bind:open
	anchor={button}
	label={label ?? 'Выбор'}
	role="listbox"
	width={button?.offsetWidth}
>
	<div id={listId} class="options">
		{#each options as o, i (o.value)}
			<button
				type="button"
				id="{listId}-{i}"
				role="option"
				tabindex="-1"
				aria-selected={o.value === value}
				class:active={i === active}
				onpointerenter={() => (active = i)}
				onclick={() => choose(o)}
			>
				<span class="txt"
					>{o.label}{#if o.hint}<span class="hint">{o.hint}</span>{/if}</span
				>
				{#if o.value === value}<Check size={16} />{/if}
			</button>
		{/each}
	</div>
</FieldPopup>

<style>
	.pick {
		display: flex;
		align-items: center;
		gap: 10px;
		color: var(--text);
		font: inherit;
		text-align: left;
		cursor: pointer;
	}
	.pick:disabled {
		opacity: 0.55;
		cursor: default;
	}
	.pick.open {
		border-color: var(--text);
		box-shadow: 0 0 0 3px var(--accent-soft);
	}
	.compact {
		min-height: 40px;
		padding: 6px 12px 6px 14px;
	}
	.value {
		flex: 1;
		min-width: 0;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.chev {
		display: grid;
		flex: none;
		color: var(--text-3);
		rotate: 90deg;
		transition: rotate var(--dur) var(--ease);
	}
	.open .chev {
		rotate: -90deg;
	}
	.options {
		display: flex;
		flex-direction: column;
		gap: 2px;
	}
	.options button {
		display: flex;
		align-items: center;
		gap: 10px;
		width: 100%;
		min-height: 40px;
		padding: 8px 12px;
		border: 0;
		border-radius: 10px;
		background: transparent;
		color: var(--text);
		font: inherit;
		font-size: 14.5px;
		text-align: left;
	}
	.options button.active {
		background: var(--surface-2);
	}
	.options button[aria-selected='true'] {
		font-weight: 600;
	}
	.options :global(svg) {
		flex: none;
		color: var(--accent);
	}
	.txt {
		flex: 1;
		display: flex;
		flex-direction: column;
	}
	.hint {
		color: var(--text-3);
		font-size: 12.5px;
		font-weight: 450;
	}
</style>
