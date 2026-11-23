import { Navigate, Outlet, useLocation } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import { Alert } from './Alert';
import { Spinner } from './Spinner';

/**
 * Navigation guard only: keeps non-admins out of the admin pages. It is not the access control; the
 * server answers 403 to any admin request from a session that is not an admin's.
 */
export function RequireAdmin() {
  const { user, loading } = useAuth();
  const location = useLocation();
  if (loading) return <Spinner label="Checking your session…" />;
  if (!user) return <Navigate to="/login" replace state={{ from: location.pathname + location.search }} />;
  if (user.role !== 'ADMIN') {
    return (
      <>
        <h1>No permission</h1>
        <Alert>You do not have permission to view this page.</Alert>
      </>
    );
  }
  return <Outlet />;
}
