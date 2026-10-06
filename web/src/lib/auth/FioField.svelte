<script lang="ts">
	import type { FullAutoFill } from 'svelte/elements';
	import { capitalError } from '$lib/fio';

	// ФИО – тремя полями: порядок «Фамилия Имя Отчество» больше не перепутать, а в базу уходит
	// одна строка, как раньше.
	let {
		value = $bindable(),
		legend = 'ФИО'
	}: {
		value: string;
		/** Подпись над полями; пусто – только для читалок (у раздела уже есть заголовок «ФИО»). */
		legend?: string;
	} = $props();

	const split = (v: string) => {
		const w = v.trim() ? v.trim().split(/\s+/) : [];
		return [w[0] ?? '', w[1] ?? '', w.slice(2).join(' ')];
	};
	const join = (p: string[]) =>
		p
			.map((x) => x.trim())
			.filter(Boolean)
			.join(' ');

	let parts = $state(split(value ?? ''));
	let last = value ?? '';
	$effect(() => {
		const next = join(parts);
		last = next;
		if (next !== value) value = next;
	});
	// Значение сбросили или подставили снаружи – раскладываем по полям.
	$effect.pre(() => {
		const v = value ?? '';
		if (v !== last) {
			last = v;
			parts = split(v);
		}
	});

	const FIELDS: { id: string; label: string; ph: string; ac: FullAutoFill; need: boolean }[] = [
		{ id: 'fio-last', label: 'Фамилия', ph: 'Иванов', ac: 'family-name', need: true },
		{ id: 'fio-first', label: 'Имя', ph: 'Иван', ac: 'given-name', need: true },
		{ id: 'fio-middle', label: 'Отчество', ph: 'Иванович', ac: 'additional-name', need: false }
	];
</script>

<fieldset class="fio">
	<legend class={legend ? 'label' : 'sr-only'}>{legend || 'ФИО'}</legend>
	<div class="grid">
		{#each FIELDS as f, i (f.id)}
			{@const problem = capitalError(parts[i])}
			<div class="cell">
				<label class="sub" for={f.id}
					>{f.label}{#if !f.need}<span class="faint">&nbsp;· если есть</span>{/if}</label
				>
				<input
					id={f.id}
					class="input"
					class:bad={!!problem}
					bind:value={parts[i]}
					autocomplete={f.ac}
					autocapitalize="words"
					maxlength="40"
					placeholder={f.ph}
					required={f.need}
					aria-invalid={!!problem}
				/>
				{#if problem}<p class="error-text small" role="alert">{problem}</p>{/if}
			</div>
		{/each}
	</div>
	<p class="hint">Так вас увидят одногруппники.</p>
</fieldset>

<style>
	.fio {
		border: 0;
		margin: 0;
		padding: 0;
		min-width: 0;
	}
	.grid {
		display: grid;
		grid-template-columns: repeat(3, minmax(0, 1fr));
		gap: 8px;
	}
	@media (max-width: 520px) {
		.grid {
			grid-template-columns: 1fr;
		}
	}
	.cell {
		display: flex;
		flex-direction: column;
		gap: 4px;
		min-width: 0;
	}
	.sub {
		font-size: 13px;
		color: var(--text-2, var(--muted));
	}
	.bad {
		border-color: var(--danger);
	}
	p {
		margin: 0;
	}
</style>
