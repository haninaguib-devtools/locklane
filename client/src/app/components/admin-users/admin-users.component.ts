import { Component, EventEmitter, HostListener, OnInit, Output, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { AdminService, AdminUser } from '../../services/admin.service';
import { AuthService } from '../../services/auth.service';
import { ConfirmDialogComponent } from '../confirm-dialog/confirm-dialog.component';

/**
 * The admin user-management panel (#240, ADR-101 Decisions 3-4): lists every account
 * and exposes creating and deleting one. `AppComponent` only renders this from the
 * account menu's "Manage users" item, itself only shown when {@link
 * AuthService.isAdmin} is true (#240) -- purely a display decision, since every
 * `/api/admin/**` request this makes is independently enforced server-side
 * (`SecurityConfig`'s `hasRole("ADMIN")`), so a non-admin who somehow reached this
 * component would still have every request rejected.
 *
 * Visually follows `settings-dialog`: a full-page backdrop that dismisses on click,
 * holding a bordered panel whose own clicks do not.
 *
 * Creating an account shows the generated temporary password exactly once, right
 * where it was created (#240) -- it is never stored anywhere in the clear after that,
 * so this is the admin's only chance to copy it down for the new account holder.
 * Deleting goes through the same {@link ConfirmDialogComponent} every other
 * destructive action in this app uses, since it is irreversible: the account, every
 * project it owned, and those projects' on-disk checkouts and sessions are all gone
 * together (ADR-101 Decision 4).
 */
@Component({
  selector: 'app-admin-users',
  standalone: true,
  imports: [FormsModule, ConfirmDialogComponent],
  templateUrl: './admin-users.component.html',
  styleUrl: './admin-users.component.css',
})
export class AdminUsersComponent implements OnInit {
  private readonly adminService = inject(AdminService);
  private readonly auth = inject(AuthService);

  @Output() closed = new EventEmitter<void>();

  @HostListener('document:keydown.escape')
  onEscape(): void {
    this.closed.emit();
  }

  readonly currentUsername = this.auth.username;

  readonly users = signal<AdminUser[]>([]);
  readonly loading = signal(true);
  readonly loadError = signal<string | null>(null);

  newUsername = '';
  newPassword = '';
  creating = false;
  createError: string | null = null;
  lastCreated: { username: string; temporaryPassword: string } | null = null;

  deleteTarget: AdminUser | null = null;
  deleting = false;
  deleteError: string | null = null;

  ngOnInit(): void {
    this.refresh();
  }

  refresh(): void {
    this.loading.set(true);
    this.loadError.set(null);
    this.adminService.list().subscribe({
      next: (users) => {
        this.loading.set(false);
        this.users.set(users);
      },
      error: (err: Error) => {
        this.loading.set(false);
        this.loadError.set(err.message);
      },
    });
  }

  createUser(): void {
    const username = this.newUsername.trim();
    if (!username || this.creating) {
      return;
    }
    this.creating = true;
    this.createError = null;
    this.lastCreated = null;
    this.adminService.create(username, this.newPassword.trim()).subscribe({
      next: (result) => {
        this.creating = false;
        this.newUsername = '';
        this.newPassword = '';
        if (result.temporaryPassword) {
          this.lastCreated = { username: result.user.username, temporaryPassword: result.temporaryPassword };
        }
        this.refresh();
      },
      error: (err: Error) => {
        this.creating = false;
        this.createError = err.message;
      },
    });
  }

  requestDelete(user: AdminUser): void {
    this.deleteError = null;
    this.deleteTarget = user;
  }

  cancelDelete(): void {
    this.deleteTarget = null;
  }

  confirmDelete(): void {
    const target = this.deleteTarget;
    if (!target || this.deleting) {
      return;
    }
    this.deleting = true;
    this.adminService.delete(target.id).subscribe({
      next: () => {
        this.deleting = false;
        this.deleteTarget = null;
        this.refresh();
      },
      error: (err: Error) => {
        this.deleting = false;
        this.deleteError = err.message;
        this.deleteTarget = null;
      },
    });
  }
}
