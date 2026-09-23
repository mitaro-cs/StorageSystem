export type Permission =
	| 'manage_instance'
	| 'assign_moderator'
	| 'assign_headman'
	| 'assign_deputy'
	| 'create_accounts'
	| 'create_invites'
	| 'block_users'
	| 'reset_passwords'
	| 'manage_subjects'
	| 'share_subjects'
	| 'publish_news'
	| 'publish_homework'
	| 'upload_materials'
	| 'suggest_materials'
	| 'moderate_content'
	| 'comment'
	| 'view_audit'
	| 'manage_permissions'
	| 'view_group';

export type GroupRole = 'headman' | 'deputy' | 'student';
export type InstanceRole = 'admin' | 'moderator';

export interface MeGroup {
	id: number;
	slug: string;
	name: string;
	university: string;
	course: number | null;
	avatar: string | null;
	archived: boolean;
	role: GroupRole | null;
	permissions: Permission[];
}

export interface Me {
	user: {
		id: number;
		username: string;
		displayName: string;
		avatar: string | null;
		instanceRole: InstanceRole | null;
		totpEnabled: boolean;
	};
	restriction: 'password_change_required' | 'totp_setup_required' | null;
	instance: { name: string; mode: 'single' | 'multi'; version: string; requireStaffTotp: boolean };
	groups: MeGroup[];
	permissions: Permission[];
}

export interface Person {
	id: number;
	displayName: string;
	avatar: string | null;
	deleted: boolean;
}

export interface GroupRef {
	id: number;
	name: string;
}

export interface SubjectRef {
	id: number;
	name: string;
	color: string;
}

export interface Subject {
	id: number;
	name: string;
	teacher: string;
	color: string;
	avatar: string | null;
	archived: boolean;
	pinned: boolean;
	groups: GroupRef[];
	can: { edit: boolean; share: boolean };
}

export interface ItemCan {
	edit: boolean;
	delete: boolean;
	hide: boolean;
}

export interface NewsItem {
	id: number;
	title: string;
	bodyMd: string;
	bodyHtml: string;
	pinned: boolean;
	urgent: boolean;
	hidden: boolean;
	createdAt: number;
	updatedAt: number;
	author: Person;
	subject: SubjectRef | null;
	groups: GroupRef[];
	comments: number;
	can: ItemCan;
}

export interface NewsPage {
	pinned: NewsItem[];
	items: NewsItem[];
	next: number | null;
}

export interface Homework {
	id: number;
	title: string;
	bodyMd: string;
	bodyHtml: string;
	dueAt: number;
	done: boolean;
	hidden: boolean;
	createdAt: number;
	updatedAt: number;
	author: Person;
	subject: SubjectRef;
	groups: GroupRef[];
	comments: number;
	attachments: FileInfo[];
	can: ItemCan;
}

export interface Today {
	upcoming: Homework[];
	overdue: Homework[];
	pinned: NewsItem[];
	news: NewsItem[];
}

export interface Comment {
	id: number;
	author: Person;
	bodyHtml: string;
	createdAt: number;
	hidden: boolean;
	canDelete: boolean;
}

export interface Member {
	userId: number;
	username: string;
	displayName: string;
	avatar: string | null;
	status: 'pending' | 'active' | 'blocked' | 'deleted';
	role: GroupRole;
	joinedAt: number;
}

export interface Invite {
	id: number;
	groupId: number;
	role: GroupRole;
	maxUses: number | null;
	uses: number;
	expiresAt: number;
	note: string;
	createdBy: number | null;
	createdAt: number;
	revokedAt: number | null;
}

export interface CreatedAccount {
	userId: number;
	username: string;
	displayName: string;
	activationPath: string | null;
	temporaryPassword: string | null;
}

export interface LinkRequest {
	id: number;
	subjectId: number;
	subjectName: string;
	fromGroupId: number;
	fromGroupName: string;
	toGroupId: number;
	toGroupName: string;
	requestedBy: number | null;
	status: string;
	createdAt: number;
}

export interface PermissionCell {
	role: GroupRole;
	permission: Permission;
	allowed: boolean;
	overridden?: boolean;
}

export interface AuditEntry {
	id: number;
	at: number;
	actorId: number | null;
	actorName: string | null;
	groupId: number | null;
	action: string;
	targetType: string | null;
	targetId: number | null;
	details: string | null;
	ip: string | null;
}

export interface FileInfo {
	id: number;
	name: string;
	mime: string;
	size: number;
}

export interface Material {
	id: number;
	subjectId: number;
	subjectName: string;
	subjectColor: string;
	folderId: number | null;
	kind: 'file' | 'link';
	title: string;
	description: string;
	url: string | null;
	file: FileInfo | null;
	author: Person;
	status: 'published' | 'pending' | 'rejected';
	hidden: boolean;
	createdAt: number;
	comments: number;
	can: { edit: boolean; delete: boolean; moderate: boolean };
}

export interface Folder {
	id: number;
	parentId: number | null;
	name: string;
	count: number;
}

export interface MaterialListing {
	path: { id: number; name: string }[];
	folders: Folder[];
	materials: Material[];
	canUpload: boolean;
	canSuggest: boolean;
}
