<script lang="ts">
	import { goto } from '$app/navigation';
	import { page } from '$app/state';
	import {
		ChevronLeft,
		ChevronRight,
		Clock,
		FileText,
		FolderOpen,
		MapPin,
		NotebookPen,
		Pencil,
		Plus,
		UserRound
	} from '@lucide/svelte';
	import { del, get, patch, put } from '$lib/api';
	import { fmtDate, fmtWeekday, fmtWeekdayShort } from '$lib/format';
	import { offline } from '$lib/offline/engine';
	import { can } from '$lib/session.svelte';
	import { toast, toastError } from '$lib/toasts.svelte';
	import type { Lesson, LessonDetail } from '$lib/types';
	import { toggleDone } from '$lib/content/homework';
	import HomeworkRow from '$lib/content/HomeworkRow.svelte';
	import MaterialRow from '$lib/content/MaterialRow.svelte';
	import { ask } from '$lib/ui/ask.svelte';
	import BackBar from '$lib/ui/BackBar.svelte';
	import Button from '$lib/ui/Button.svelte';
	import Empty from '$lib/ui/Empty.svelte';
	import Menu, { type MenuItem } from '$lib/ui/Menu.svelte';
	import Skeleton from '$lib/ui/Skeleton.svelte';
	import SubjectGlyph from '$lib/ui/SubjectGlyph.svelte';
	import {
		lessonKind,
		lessonName,
		lessonProgress,
		lessonState,
		lessonTime,
		untilText,
		weekStart
	} from '$lib/schedule/lessons';

	// Пара: когда, где, у кого, тема; задания к ней и материалы (слайды, конспект). Добавить задание
	// или файл отсюда — они сразу привязаны к паре.
	let data = $state<LessonDetail | null>(null);
	let missing = $state(false);
	let editor = $state(false);
	let hwOpen = $state(false);
	let fileOpen = $state(false);
	let noteEdit = $state(false);
	let noteDraft = $state('');
	let savingNote = $state(false);
	const now = Date.now();

	async function load() {
		try {
			data = await get<LessonDetail>(`/api/lessons/${page.params.id}`);
			missing = false;
		} catch {
			missing = true;
		}
	}

	$effect(() => {
		void page.params.id;
		void offline.version;
		load();
	});

	const l = $derived(data?.lesson ?? null);
	const status = $derived(l ? lessonState(l, now) : 'later');
	const kind = $derived(lessonKind(l?.kind));
	const canHomework = $derived(!!l?.subject && can('publish_homework', l.groupId));
	const canUpload = $derived(!!l?.subject && can('upload_materials', l.groupId));
	const canFiles = $derived(canUpload || (!!l?.subject && can('suggest_materials', l.groupId)));
	const cap = (s: string) => s.charAt(0).toUpperCase() + s.slice(1);
	const iso = (ms: number) => {
		const d = new Date(weekStart(ms));
		const p = (n: number) => String(n).padStart(2, '0');
		return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
	};

	const actions = $derived.by((): MenuItem[] => {
		if (!l?.can.edit) return [];
		const lesson = l;
		return [
			{ label: 'Изменить пару', onclick: () => (editor = true) },
			{
				label: lesson.cancelled ? 'Вернуть пару' : 'Пары не было',
				onclick: async () => {
					try {
						const updated = await put<Lesson>(`/api/lessons/${lesson.id}/cancelled`, {
							value: !lesson.cancelled
						});
						if (data) data.lesson = updated;
					} catch (e) {
						toastError(e);
					}
				}
			},
			{
				label: 'Удалить пару',
				danger: true,
				onclick: async () => {
					if (
						!(await ask('Задания и материалы к ней останутся — просто без пары.', {
							title: 'Удалить пару?',
							ok: 'Удалить',
							danger: true
						}))
					)
						return;
					try {
						await del(`/api/lessons/${lesson.id}`);
						toast('Пара удалена', 'ok');
						goto(`/schedule?week=${iso(lesson.startsAt)}`, { replaceState: true });
					} catch (e) {
						toastError(e);
					}
				}
			}
		];
	});

	async function saveNote() {
		if (!l) return;
		savingNote = true;
		try {
			const updated = await patch<Lesson>(`/api/lessons/${l.id}`, { note: noteDraft });
			if (data) data.lesson = updated;
			noteEdit = false;
		} catch (e) {
			toastError(e);
		} finally {
			savingNote = false;
		}
	}

	const neighbor = (n: { startsAt: number; kind: string }) =>
		`${lessonKind(n.kind).label}, ${fmtWeekdayShort(n.startsAt)} ${fmtDate(n.startsAt, now)}`;
</script>

<svelte:head><title>{l ? `${lessonName(l)} · ${kind.label}` : 'Пара'} · campus</title></svelte:head>

<BackBar href={l ? `/schedule?week=${iso(l.startsAt)}` : '/schedule'} label="Расписание"
	><Menu items={actions} label="Действия с парой" /></BackBar
>

{#if missing}
	<div class="card">
		<Empty title="Пара не найдена" text="Её удалили из расписания или она другой группы." />
	</div>
{:else if !data || !l}
	<Skeleton lines={6} />
{:else}
	<header class="hero {status}" style:--subject={l.subject?.color ?? 'var(--text-3)'}>
		<div class="top">
			{#if l.subject}
				<a class="glyph" href="/subjects/{l.subject.id}" aria-label="Предмет: {l.subject.name}"
					><SubjectGlyph
						id={l.subject.id}
						name={l.subject.name}
						color={l.subject.color}
						size={52}
					/></a
				>
			{/if}
			<div class="titles">
				<span class="kind">{kind.label}</span>
				<h1>{lessonName(l)}</h1>
			</div>
			{#if l.cancelled}<span class="chip danger">пары не было</span>
			{:else if status === 'now'}<span class="chip live"><i class="dot"></i>идёт</span>
			{:else if status === 'soon'}<span class="chip amber num">{untilText(l.startsAt - now)}</span>
			{:else if status === 'past'}<span class="chip">прошла</span>{/if}
		</div>
		<div class="facts">
			<span
				><Clock size={16} />
				<span class="num"
					>{cap(fmtWeekday(l.startsAt))}, {fmtDate(l.startsAt, now)} · {lessonTime(l)}</span
				></span
			>
			{#if l.place}<span><MapPin size={16} /> {l.place}</span>{/if}
			{#if l.teacher}<span><UserRound size={16} /> {l.teacher}</span>{/if}
		</div>
		{#if status === 'now'}<span class="progress" style:--p={lessonProgress(l, now)}></span>{/if}
	</header>

	{#if data.prev || data.next}
		<nav class="neighbors" aria-label="Соседние пары предмета">
			{#if data.prev}<a class="pill" href="/schedule/{data.prev.id}" data-sveltekit-replacestate
					><ChevronLeft size={16} /> <span class="num">{neighbor(data.prev)}</span></a
				>{:else}<span></span>{/if}
			{#if data.next}<a class="pill" href="/schedule/{data.next.id}" data-sveltekit-replacestate
					><span class="num">{neighbor(data.next)}</span> <ChevronRight size={16} /></a
				>{/if}
		</nav>
	{/if}

	{#if l.can.edit}
		<div class="toolbar" role="toolbar" aria-label="Пара">
			<Button size="s" variant="ghost" onclick={() => (editor = true)}
				><Pencil size={15} /> Изменить пару</Button
			>
		</div>
	{/if}

	<div class="parts" style:--subject={l.subject?.color ?? 'var(--accent)'}>
		<section class="card part note-part" aria-labelledby="part-note">
			<div class="part-head">
				<span class="ico" aria-hidden="true"><FileText size={18} /></span>
				<h2 id="part-note">Тема и заметки</h2>
				{#if l.can.edit && !noteEdit}
					<Button size="s" variant="ghost" onclick={() => ((noteDraft = l.note), (noteEdit = true))}
						><Pencil size={14} /> {l.note ? 'Изменить' : 'Добавить'}</Button
					>
				{/if}
			</div>
			{#if noteEdit}
				<textarea
					class="textarea"
					rows="4"
					maxlength="2000"
					bind:value={noteDraft}
					aria-label="Тема и заметки"
					placeholder="Лекция 3. Производные — принести калькулятор"></textarea>
				<div class="row note-actions">
					<Button size="s" onclick={() => (noteEdit = false)}>Отмена</Button>
					<Button size="s" variant="primary" loading={savingNote} onclick={saveNote}
						>Сохранить</Button
					>
				</div>
			{:else if l.note}
				<p class="note">{l.note}</p>
			{:else}
				<p class="faint empty">Тема не указана.</p>
			{/if}
		</section>

		<section class="card part" aria-labelledby="part-hw">
			<div class="part-head">
				<span class="ico" aria-hidden="true"><NotebookPen size={18} /></span>
				<h2 id="part-hw">Задания</h2>
				{#if data.homework.length}<span class="count num">{data.homework.length}</span>{/if}
				{#if canHomework}
					<Button size="s" variant="ghost" onclick={() => (hwOpen = true)}
						><Plus size={16} /> Добавить</Button
					>
				{/if}
			</div>
			{#if data.homework.length}
				<div class="list">
					{#each data.homework as h (h.id)}
						<HomeworkRow item={h} {now} ontoggle={toggleDone} />
					{/each}
				</div>
			{:else}
				<p class="faint empty">
					К этой паре ничего не задано{canHomework ? ' — добавьте, если задали' : ''}.
				</p>
			{/if}
		</section>

		<section class="card part" aria-labelledby="part-files">
			<div class="part-head">
				<span class="ico" aria-hidden="true"><FolderOpen size={18} /></span>
				<h2 id="part-files">Материалы</h2>
				{#if data.materials.length}<span class="count num">{data.materials.length}</span>{/if}
				{#if canFiles}
					<Button size="s" variant="ghost" onclick={() => (fileOpen = true)}
						><Plus size={16} /> {canUpload ? 'Добавить' : 'Предложить'}</Button
					>
				{/if}
			</div>
			{#if data.materials.length}
				<div class="list">
					{#each data.materials as m (m.id)}<MaterialRow {m} />{/each}
				</div>
			{:else}
				<p class="faint empty">
					Слайдов и конспектов пока нет{canFiles ? ' — загрузите, и они будут и в предмете' : ''}.
				</p>
			{/if}
		</section>
	</div>

	{#if hwOpen && l.subject}
		{#await import('$lib/content/HomeworkComposer.svelte') then m}
			<m.default
				bind:open={hwOpen}
				lesson={{ id: l.id, startsAt: l.startsAt, groupId: l.groupId, subjectId: l.subject.id }}
				onsaved={load}
			/>
		{/await}
	{/if}
	{#if fileOpen && l.subject}
		{#await import('$lib/content/MaterialAdd.svelte') then m}
			<m.default
				bind:open={fileOpen}
				subjectId={l.subject.id}
				folderId={null}
				suggest={!canUpload}
				lessonId={l.id}
				onsaved={load}
			/>
		{/await}
	{/if}
	{#if editor}
		{#await import('$lib/schedule/LessonEditor.svelte') then m}
			<m.default
				bind:open={editor}
				groupId={l.groupId}
				edit={l}
				onsaved={(x) => data && ((data.lesson = x), load())}
			/>
		{/await}
	{/if}
{/if}

<style>
	.hero {
		position: relative;
		display: flex;
		flex-direction: column;
		gap: var(--s4);
		margin-bottom: var(--s3);
		padding: var(--s5);
		border-radius: var(--r-xl);
		background:
			radial-gradient(
				120% 140% at 0% 0%,
				color-mix(in srgb, var(--subject) 22%, transparent),
				transparent 60%
			),
			var(--surface);
		box-shadow:
			inset 0 0 0 1px color-mix(in srgb, var(--subject) 22%, var(--border)),
			var(--shadow-2);
		overflow: hidden;
	}
	.top {
		display: flex;
		align-items: flex-start;
		gap: var(--s4);
	}
	.glyph {
		flex: none;
		line-height: 0;
	}
	.titles {
		flex: 1;
		min-width: 0;
		display: flex;
		flex-direction: column;
		gap: 4px;
	}
	.kind {
		align-self: flex-start;
		padding: 2px 10px;
		border-radius: var(--r-full);
		background: color-mix(in srgb, var(--subject) 18%, transparent);
		font-size: 13px;
		font-weight: 650;
	}
	.hero h1 {
		font-size: clamp(24px, 5vw, 32px);
		overflow-wrap: break-word;
		text-wrap: balance;
	}
	.facts {
		display: flex;
		flex-wrap: wrap;
		gap: 8px 20px;
		color: var(--text-2);
		font-size: 15px;
	}
	.facts > span {
		display: inline-flex;
		align-items: center;
		gap: 7px;
	}
	.chip.live {
		gap: 6px;
		background: var(--text);
		color: var(--bg);
		font-weight: 650;
	}
	.dot {
		width: 7px;
		height: 7px;
		border-radius: 50%;
		background: var(--subject);
	}
	.progress {
		position: absolute;
		left: 0;
		bottom: 0;
		width: calc(var(--p) * 100%);
		height: 4px;
		background: var(--subject);
	}
	.past {
		opacity: 0.85;
	}
	.neighbors {
		display: flex;
		justify-content: space-between;
		gap: 8px;
		margin-bottom: var(--s3);
		flex-wrap: wrap;
	}
	.neighbors .pill {
		max-width: 100%;
	}
	.toolbar {
		display: flex;
		flex-wrap: wrap;
		gap: 8px;
		margin: var(--s3) 0 var(--s5);
	}
	/* Части пары — отдельными карточками: тема на всю ширину, задания и материалы рядом. */
	.parts {
		display: grid;
		gap: var(--s4);
		grid-template-columns: 1fr;
	}
	@media (min-width: 1100px) {
		.parts {
			grid-template-columns: 1fr 1fr;
		}
		.note-part {
			grid-column: 1 / -1;
		}
	}
	.part {
		display: flex;
		flex-direction: column;
		gap: var(--s3);
		padding: var(--s4);
		min-width: 0;
	}
	.part-head {
		display: flex;
		align-items: center;
		gap: 10px;
		min-height: 36px;
	}
	.part-head h2 {
		margin: 0;
		font-size: 17px;
	}
	.part-head :global(.btn) {
		margin-left: auto;
	}
	.ico {
		display: grid;
		place-items: center;
		width: 32px;
		height: 32px;
		flex: none;
		border-radius: 10px;
		background: color-mix(in srgb, var(--subject, var(--accent)) 16%, var(--surface-2));
		color: var(--text);
	}
	.count {
		padding: 1px 8px;
		border-radius: var(--r-full);
		background: var(--surface-2);
		font-size: 13px;
		font-weight: 650;
		color: var(--text-2);
	}
	.part .list {
		margin: 0 calc(-1 * var(--s2));
	}
	.note {
		margin: 0;
		white-space: pre-wrap;
		overflow-wrap: anywhere;
	}
	.note-actions {
		justify-content: flex-end;
		gap: 8px;
	}
	.empty {
		margin: 0;
		padding: var(--s3);
		border: 1px dashed var(--border);
		border-radius: var(--r);
		text-align: center;
		font-size: 14px;
	}
	@media (max-width: 520px) {
		.hero {
			padding: var(--s4);
		}
	}
</style>
