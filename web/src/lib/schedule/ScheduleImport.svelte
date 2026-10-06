<script lang="ts">
	import { untrack } from 'svelte';
	import { CalendarDays, FileUp, TriangleAlert } from '@lucide/svelte';
	import { post } from '$lib/api';
	import { loadSubjects, subjects } from '$lib/data.svelte';
	import { fmtDate, plural } from '$lib/format';
	import { can } from '$lib/session.svelte';
	import { toast } from '$lib/toasts.svelte';
	import type { MeGroup, SchedulePreview } from '$lib/types';
	import Button from '$lib/ui/Button.svelte';
	import Modal from '$lib/ui/Modal.svelte';
	import { kindsText } from './lessons';

	// Расписание из файла календаря (.ics): выбрать файл → посмотреть, что в нём и к каким
	// предметам группы относятся пары → загрузить. Разбирает файл сервер (schedule/Ics.java).
	let {
		open = $bindable(),
		group,
		onsaved
	}: { open: boolean; group: MeGroup; onsaved: () => void } = $props();

	let ics = $state('');
	let fileName = $state('');
	let preview = $state<SchedulePreview | null>(null);
	/** Название из файла → «subject:ID», «create», «none» или «skip». */
	let choice = $state<Record<string, string>>({});
	let replace = $state(true);
	let busy = $state(false);
	let error = $state('');
	let dragging = $state(false);

	const groupSubjects = $derived(
		subjects.list.filter((s) => !s.archived && s.groups.some((g) => g.id === group.id))
	);
	const canCreate = $derived(can('manage_subjects', group.id));

	$effect(() => {
		if (!open) return;
		untrack(() => {
			ics = fileName = error = '';
			preview = null;
			choice = {};
		});
	});

	/** Файл – текстом: календари в UTF-8, но выгрузки старых программ бывают в Windows-1251. */
	async function text(file: File): Promise<string> {
		const buf = await file.arrayBuffer();
		try {
			return new TextDecoder('utf-8', { fatal: true }).decode(buf);
		} catch {
			return new TextDecoder('windows-1251').decode(buf);
		}
	}

	async function pick(file: File | undefined) {
		if (!file) return;
		error = '';
		if (file.size > 3 * 1024 * 1024) {
			error = 'Файл больше 3 МБ – это не расписание одной группы';
			return;
		}
		busy = true;
		try {
			const t = await text(file);
			const p = await post<SchedulePreview>(`/api/groups/${group.id}/schedule/preview`, {
				ics: t
			});
			ics = t;
			fileName = file.name;
			preview = p;
			replace = true;
			// Не нашёлся предмет: регулярная пара – новый предмет, разовое событие – без предмета.
			choice = Object.fromEntries(
				p.titles.map((x) => [
					x.key,
					x.subjectId ? `subject:${x.subjectId}` : canCreate && x.count >= 3 ? 'create' : 'none'
				])
			);
		} catch (e) {
			error = e instanceof Error ? e.message : 'Файл не прочитать';
		} finally {
			busy = false;
		}
	}

	function drop(e: DragEvent) {
		e.preventDefault();
		dragging = false;
		pick(e.dataTransfer?.files[0]);
	}

	async function save() {
		if (!preview) return;
		busy = true;
		error = '';
		try {
			const choices = preview.titles.map((t) => {
				const c = choice[t.key] ?? 'none';
				return c.startsWith('subject:')
					? { key: t.key, action: 'subject', subjectId: Number(c.slice(8)) }
					: { key: t.key, action: c };
			});
			const r = await post<{
				created: number;
				updated: number;
				deleted: number;
				skipped: number;
				subjects: number;
			}>(`/api/groups/${group.id}/schedule/import`, { ics, choices, replace });
			const parts = [
				r.created && `новых пар: ${r.created}`,
				r.updated && `изменилось: ${r.updated}`,
				r.deleted && `убрано: ${r.deleted}`,
				r.subjects && `новых предметов: ${r.subjects}`
			].filter(Boolean);
			toast(
				parts.length ? `Расписание загружено: ${parts.join(', ')}` : 'Всё уже было в расписании',
				'ok'
			);
			if (r.subjects) loadSubjects();
			open = false;
			onsaved();
		} catch (e) {
			error = e instanceof Error ? e.message : 'Не загрузилось';
		} finally {
			busy = false;
		}
	}

	const skipped = $derived(
		preview
			? [
					preview.allDay &&
						`${preview.allDay} ${plural(preview.allDay, ['событие', 'события', 'событий'])} на весь день (праздники, сессия) – не пары`,
					preview.past &&
						`${preview.past} ${plural(preview.past, ['прошедшая пара', 'прошедшие пары', 'прошедших пар'])} старше четырёх месяцев`,
					preview.cancelled &&
						`${preview.cancelled} ${plural(preview.cancelled, ['отменённая', 'отменённые', 'отменённых'])}`,
					preview.unsupported &&
						`у ${preview.unsupported} ${plural(preview.unsupported, ['события', 'событий', 'событий'])} сложное повторение – взяты только первые пары`
				].filter(Boolean)
			: []
	);
</script>

<Modal bind:open title="Расписание из файла календаря" wide dirty={!!preview}>
	{#if !preview}
		<label
			class="drop"
			class:dragging
			class:busy
			ondragover={(e) => {
				e.preventDefault();
				dragging = true;
			}}
			ondragleave={() => (dragging = false)}
			ondrop={drop}
		>
			<span class="icon"><FileUp size={28} /></span>
			<strong>{busy ? 'Читаем файл…' : 'Выберите файл .ics'}</strong>
			<span class="muted">или перетащите его сюда</span>
			<input
				class="sr-only"
				type="file"
				accept=".ics,.ical,.ifb,.icalendar,text/calendar"
				disabled={busy}
				onchange={(e) => {
					const f = e.currentTarget.files?.[0];
					e.currentTarget.value = '';
					pick(f);
				}}
			/>
		</label>
		{#if error}<p class="error-text" role="alert">{error}</p>{/if}
		<div class="where">
			<p class="label">Где взять файл</p>
			<ul class="muted">
				<li>Сайт расписания вуза – кнопка «Экспорт», «Скачать .ics» или «В календарь».</li>
				<li>Google Календарь – Настройки → Импорт и экспорт → Экспорт.</li>
				<li>Яндекс Календарь – настройки календаря → Экспорт.</li>
				<li>Календарь на Mac – Файл → Экспорт; Outlook – Файл → Сохранить календарь.</li>
			</ul>
			<p class="hint">
				Повторы («каждую неделю», «через неделю»), переносы и отмены пар файл передаёт сам.
				Загрузите обновлённый файл ещё раз – пары обновятся, темы, задания и материалы к ним
				останутся.
			</p>
		</div>
	{:else}
		<div class="summary">
			<span class="icon"><CalendarDays size={22} /></span>
			<div>
				<strong
					>{preview.lessons}
					{plural(preview.lessons, ['пара', 'пары', 'пар'])} · с {fmtDate(preview.from)} по {fmtDate(
						preview.to
					)}</strong
				>
				<span class="faint small">{fileName} · {group.name}</span>
			</div>
			<button class="linklike small" onclick={() => (preview = null)}>Другой файл</button>
		</div>
		{#if skipped.length}
			<p class="hint">Не загружаем: {skipped.join('; ')}.</p>
		{/if}

		<p class="label">Какие это предметы</p>
		<div class="titles list">
			{#each preview.titles as t (t.key)}
				<div class="title-row">
					<div class="t-info">
						<strong>{t.name}</strong>
						<span class="faint small"
							>{kindsText(t.kinds)}{t.teacher ? ` · ${t.teacher}` : ''}{t.places.length
								? ` · ${t.places.join(', ')}`
								: ''}</span
						>
					</div>
					<select
						class="select"
						bind:value={choice[t.key]}
						aria-label="Предмет для «{t.name}»"
						class:skip={choice[t.key] === 'skip'}
					>
						<optgroup label="Предметы группы">
							{#each groupSubjects as s (s.id)}<option value="subject:{s.id}">{s.name}</option
								>{/each}
						</optgroup>
						{#if canCreate}<option value="create">Новый предмет «{t.name}»</option>{/if}
						<option value="none">Без предмета</option>
						<option value="skip">Не загружать</option>
					</select>
				</div>
			{/each}
		</div>

		{#if preview.replaced > 0}
			<label class="check replace">
				<input type="checkbox" bind:checked={replace} />
				<span
					>Убрать {preview.replaced}
					{plural(preview.replaced, ['пару', 'пары', 'пар'])} прежней загрузки, которых нет в этом файле
					<span class="faint small"
						>(с {fmtDate(preview.from)} и дальше). Задания и материалы к ним останутся.</span
					></span
				>
			</label>
		{:else if preview.existing > 0}
			<p class="hint">
				<TriangleAlert size={14} /> У группы уже есть пары – одинаковые не задвоятся: загрузка обновит
				их.
			</p>
		{/if}
		{#if error}<p class="error-text" role="alert">{error}</p>{/if}
	{/if}

	{#snippet footer()}
		<Button onclick={() => (open = false)}>Отмена</Button>
		{#if preview}
			<Button variant="primary" loading={busy} onclick={save}>
				Загрузить {preview.lessons}
				{plural(preview.lessons, ['пару', 'пары', 'пар'])}
			</Button>
		{/if}
	{/snippet}
</Modal>

<style>
	.drop {
		display: flex;
		flex-direction: column;
		align-items: center;
		gap: 6px;
		padding: var(--s6) var(--s4);
		border: 2px dashed var(--border-strong);
		border-radius: var(--r-l);
		background: var(--surface-2);
		text-align: center;
		cursor: pointer;
		transition:
			border-color var(--dur) var(--ease),
			background-color var(--dur) var(--ease);
	}
	.drop:hover,
	.drop.dragging {
		border-color: var(--accent);
		background: color-mix(in srgb, var(--accent) 6%, var(--surface-2));
	}
	.drop.busy {
		cursor: progress;
		opacity: 0.7;
	}
	.drop:focus-within {
		outline: 2px solid var(--focus);
		outline-offset: 2px;
	}
	.icon {
		display: grid;
		place-items: center;
		width: 52px;
		height: 52px;
		border-radius: 16px;
		background: var(--surface);
		color: var(--text);
		box-shadow: var(--shadow-1);
	}
	.drop strong {
		font-size: 16px;
	}
	.where {
		margin-top: var(--s4);
	}
	.where ul {
		margin: 0 0 var(--s3);
		padding-left: 20px;
		font-size: 14px;
		line-height: 1.6;
	}
	.summary {
		display: flex;
		align-items: center;
		gap: 12px;
		margin-bottom: var(--s3);
	}
	.summary > div {
		flex: 1;
		min-width: 0;
		display: flex;
		flex-direction: column;
	}
	.summary .icon {
		width: 44px;
		height: 44px;
		border-radius: 14px;
	}
	.titles {
		margin-bottom: var(--s4);
	}
	.title-row {
		display: grid;
		grid-template-columns: minmax(0, 1fr) minmax(0, 260px);
		align-items: center;
		gap: 12px;
		padding: 10px var(--s4);
		background: var(--surface);
	}
	.title-row + .title-row {
		border-top: 1px solid var(--border);
	}
	.t-info {
		display: flex;
		flex-direction: column;
		min-width: 0;
	}
	.t-info strong {
		overflow-wrap: anywhere;
	}
	.select.skip {
		color: var(--text-3);
	}
	.replace {
		align-items: flex-start;
		gap: 10px;
	}
	.label {
		margin: var(--s3) 0 8px;
		font-weight: 620;
	}
	@media (max-width: 560px) {
		.title-row {
			grid-template-columns: 1fr;
			gap: 8px;
		}
	}
</style>
